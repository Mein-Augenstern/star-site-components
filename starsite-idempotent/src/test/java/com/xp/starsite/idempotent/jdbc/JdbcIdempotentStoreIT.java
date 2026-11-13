/*
 * Copyright 2026 muyi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.xp.starsite.idempotent.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xp.starsite.idempotent.api.IdempotentStatus;
import com.xp.starsite.idempotent.store.ClaimOutcome;
import com.xp.starsite.idempotent.store.IdempotentSql;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * JDBC 存储真库 IT（Testcontainers mysql:8.0；TEST_MYSQL_URL 外部库逃生口——与
 * async-command/schedule IT 同形）。验证：真 DDL 建表 / uk 认领互斥 /
 * 三合一回收 / 双条件守卫 / 两步清理 / 表名白名单 / 陈旧结构 fail-fast。
 *
 * @author muyi
 * @date 2026/11/12
 */
class JdbcIdempotentStoreIT {

    /** 外部库直连地址（无 docker 环境的逃生口） */
    private static final String EXT_URL = System.getenv("TEST_MYSQL_URL");

    /** 一次性 MySQL 容器 */
    private static MySQLContainer<?> container;

    /** 被测存储 */
    private static JdbcIdempotentStore store;

    /** 测试 id 发号（sequence 组件的替身——真库只关心 BIGINT 唯一性） */
    private static final AtomicLong IDS = new AtomicLong(5000);

    /** 基准时刻（回收/过期判定锚） */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 10, 0, 0);

    /** 起容器/数据源/建表（先 DROP 旧表——外部库逃生口形态下可重跑，残留行会撞 uk） */
    @BeforeAll
    static void startDb() throws Exception {
        if (EXT_URL == null) {
            container = new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("starsite").withUsername("test").withPassword("test");
            container.start();
        }
        String url = EXT_URL != null ? EXT_URL : container.getJdbcUrl();
        String user = EXT_URL != null ? System.getenv().getOrDefault("TEST_MYSQL_USER", "root") : "test";
        String pass = EXT_URL != null ? System.getenv().getOrDefault("TEST_MYSQL_PASSWORD", "root") : "test";
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(com.mysql.cj.jdbc.Driver.class);
        ds.setUrl(url);
        ds.setUsername(user);
        ds.setPassword(pass);
        // 清残留（Testcontainers 每次新容器无残留；TEST_MYSQL_URL 外部库重跑会残留）
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(url, user, pass);
                java.sql.Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS idempotent_record");
            st.execute("DROP TABLE IF EXISTS idempotent_stale_it");
        }
        store = new JdbcIdempotentStore(new JdbcTemplate(ds), "idempotent_record",
                new IdempotentSql.MySql(), true, IDS::incrementAndGet);
    }

    /** 收容器 */
    @AfterAll
    static void stopDb() {
        if (container != null) {
            container.stop();
        }
    }

    /** 真 DDL 建表 + uk 认领互斥（DuplicateKey 翻译 duplicate） */
    @Test
    void claimExactlyOnceUnderUniqueKey() {
        LocalDateTime expire = NOW.plusHours(24);
        ClaimOutcome first = store.tryClaim("it", "uk-1", expire, "it:1");
        assertThat(first.claimed()).isTrue();
        assertThat(store.tryClaim("it", "uk-1", expire, "it:2").claimed()).isFalse();
        // 行落库可见
        var row = store.find("it", "uk-1").orElseThrow();
        assertThat(row.getStatus()).isEqualTo(IdempotentStatus.PROCESSING);
        assertThat(row.getHost()).isEqualTo("it:1");
        assertThat(row.getGmtCreate()).isNotNull();
    }

    /** 终态双条件守卫 + FAILED 释放键回收重置 */
    @Test
    void terminalGuardAndFailedReclaim() {
        LocalDateTime expire = NOW.plusHours(24);
        ClaimOutcome c = store.tryClaim("it", "f-1", expire, "it:1");
        assertThat(store.complete("it", "f-1", expire, "v1")).isTrue();
        assertThat(store.complete("it", "f-1", expire, "v2")).isFalse(); // DONE 后拒写
        // FAILED 路径
        ClaimOutcome f = store.tryClaim("it", "f-2", expire, "it:1");
        assertThat(store.fail("it", "f-2", expire, "boom")).isTrue();
        var reclaimed = store.tryReclaim("it", "f-2", NOW.plusHours(24), NOW, NOW.minusSeconds(120), "it:3");
        assertThat(reclaimed).isPresent();
        assertThat(reclaimed.orElseThrow().getStatus()).isEqualTo(IdempotentStatus.PROCESSING);
        assertThat(reclaimed.orElseThrow().getError()).isNull(); // 重置清空
    }

    /** 过期 DONE（键遗忘）回收 + 两步清理 */
    @Test
    void expiredReclaimAndPurge() {
        LocalDateTime expire = NOW.plusHours(24);
        ClaimOutcome c = store.tryClaim("it", "p-1", expire, "it:1");
        store.complete("it", "p-1", expire, "old");
        // 窗口内不可回收
        assertThat(store.tryReclaim("it", "p-1", NOW.plusHours(24), NOW, NOW.minusSeconds(120), "it:2"))
                .isEmpty();
        // 窗口外回收（换代 expire=later+24h）+ 清理（阈值须严格大于新 expire——< 不含等号）
        LocalDateTime later = NOW.plusHours(25);
        assertThat(store.tryReclaim("it", "p-1", later.plusHours(24), later,
                later.minusSeconds(120), "it:2")).isPresent();
        assertThat(store.purgeExpired(10, later.plusHours(25))).isGreaterThanOrEqualTo(1);
        assertThat(store.find("it", "p-1")).isEmpty();
    }

    /** 表名白名单（构造期先于 jdbc 解引用校验——传 null 模板安全） */
    @Test
    void tableNameWhitelist() {
        assertThatThrownBy(() -> new JdbcIdempotentStore(null, "bad-name",
                new IdempotentSql.MySql(), false, IDS::incrementAndGet))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("字母数字下划线");
    }

    /** 陈旧结构 fail-fast（表存在缺契约列）——BUG-009 语义 */
    @Test
    void staleSchemaFailsFast() throws Exception {
        // 手工建陈旧表（缺 9 列）
        try (var conn = java.sql.DriverManager.getConnection(
                EXT_URL != null ? EXT_URL : container.getJdbcUrl(),
                EXT_URL != null ? "root" : "test",
                EXT_URL != null ? "root" : "test");
                var st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS idempotent_stale_it (id BIGINT NOT NULL PRIMARY KEY, "
                    + "idem_key VARCHAR(128) NOT NULL)");
        }
        var ds = new SimpleDriverDataSource();
        ds.setDriverClass(com.mysql.cj.jdbc.Driver.class);
        ds.setUrl(EXT_URL != null ? EXT_URL : container.getJdbcUrl());
        ds.setUsername(EXT_URL != null ? "root" : "test");
        ds.setPassword(EXT_URL != null ? "root" : "test");
        assertThatThrownBy(() -> new JdbcIdempotentStore(new JdbcTemplate(ds), "idempotent_stale_it",
                new IdempotentSql.MySql(), true, IDS::incrementAndGet))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少列")
                .hasMessageContaining("idempotent_record_ddl.sql");
    }
}
