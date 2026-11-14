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
package com.xp.starsite.quota.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xp.starsite.quota.store.QuotaSql;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * JDBC 存储真库 IT（Testcontainers mysql:8.0；TEST_MYSQL_URL 外部库逃生口——家族同形）。
 * 验证：真 DDL 建表 / uk 建户互斥幂等 / 五原子操作条件拒绝（真 MySQL 行级锁下）/
 * 降额保护 / 表名白名单 / 陈旧结构 fail-fast。
 *
 * @author muyi
 * @date 2026/11/14
 */
class JdbcQuotaStoreIT {

    /** 外部库直连地址（无 docker 环境的逃生口） */
    private static final String EXT_URL = System.getenv("TEST_MYSQL_URL");

    /** 一次性 MySQL 容器 */
    private static MySQLContainer<?> container;

    /** 被测存储 */
    private static JdbcQuotaStore store;

    /** 测试 id 发号（sequence 替身） */
    private static final AtomicLong IDS = new AtomicLong(9000);

    /** 建库时间源（系统时钟） */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 14, 10, 0, 0);

    /** 起容器/数据源/建表（先 DROP 旧表——外部库重跑残留会撞 uk） */
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
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(url, user, pass);
                java.sql.Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS quota_account");
            st.execute("DROP TABLE IF EXISTS quota_stale_it");
        }
        store = new JdbcQuotaStore(new JdbcTemplate(ds), "quota_account",
                new QuotaSql.MySql(), true, IDS::incrementAndGet);
    }

    /** 收容器 */
    @AfterAll
    static void stopDb() {
        if (container != null) {
            container.stop();
        }
    }

    /** 真 DDL 建表 + uk 建户幂等（重复建户保留现量） */
    @Test
    void initIdempotentUnderUniqueKey() {
        assertThat(store.init("it:credit:A", 1000).getAvailable()).isEqualTo(1000);
        store.occupy("it:credit:A", 200);
        // 重复建户：保留现量（不重置账面）
        com.xp.starsite.quota.api.QuotaBalance again = store.init("it:credit:A", 9999);
        assertThat(again.getTotal()).isEqualTo(1000);
        assertThat(again.getOccupied()).isEqualTo(200);
    }

    /** 五原子操作条件拒绝（真 MySQL 行级锁下）：不变式/充足性/降额保护逐条验证 */
    @Test
    void atomicOperationsUnderRealMySQL() {
        assertThat(store.init("it:credit:B", 1000).getAvailable()).isEqualTo(1000);
        // 不变式边界：恰好可占用
        assertThat(store.occupy("it:credit:B", 1000).get().getAvailable()).isZero();
        assertThat(store.occupy("it:credit:B", 1)).isEmpty(); // 超量拒绝
        // 释放回补 + 充足性拒绝
        assertThat(store.release("it:credit:B", 400).get().getAvailable()).isEqualTo(400);
        assertThat(store.release("it:credit:B", 700)).isEmpty(); // 只剩 600
        // 冻结/解冻/转销换位
        assertThat(store.freeze("it:credit:B", 400).get().getFrozen()).isEqualTo(400);
        assertThat(store.unfreeze("it:credit:B", 400).get().getFrozen()).isZero();
        assertThat(store.freeze("it:credit:B", 300).get().getFrozen()).isEqualTo(300);
        com.xp.starsite.quota.api.QuotaBalance confirmed = store.confirm("it:credit:B", 300).get();
        assertThat(confirmed.getFrozen()).isZero();
        assertThat(confirmed.getOccupied()).isEqualTo(900); // 600 占用 + 300 换入
        assertThat(confirmed.getAvailable()).isEqualTo(100); // 换位不改可用量
        // 降额保护：恰好覆盖通过，越界拒绝（900 占用/总量 1000，可用 100）
        assertThat(store.adjustTotal("it:credit:B", -100).get().getTotal()).isEqualTo(900);
        assertThat(store.adjustTotal("it:credit:B", -1)).isEmpty(); // 900-1 < 900 占用
        store.adjustTotal("it:credit:B", 100); // 回补总量，后续用例账面干净
    }

    /** 账户不存在：条件 UPDATE 不命中（行不在）→ empty */
    @Test
    void absentAccountReturnsEmpty() {
        assertThat(store.find("it:absent")).isEmpty();
        assertThat(store.occupy("it:absent", 1)).isEmpty();
        assertThat(store.release("it:absent", 1)).isEmpty();
    }

    /** 表名白名单 */
    @Test
    void tableNameWhitelist() {
        assertThatThrownBy(() -> new JdbcQuotaStore(null, "bad-name",
                new QuotaSql.MySql(), false, IDS::incrementAndGet))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("字母数字下划线");
    }

    /** 陈旧结构 fail-fast（BUG-009 语义） */
    @Test
    void staleSchemaFailsFast() throws Exception {
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                EXT_URL != null ? EXT_URL : container.getJdbcUrl(),
                EXT_URL != null ? "root" : "test",
                EXT_URL != null ? "root" : "test");
                java.sql.Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS quota_stale_it (id BIGINT NOT NULL PRIMARY KEY)");
        }
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(com.mysql.cj.jdbc.Driver.class);
        ds.setUrl(EXT_URL != null ? EXT_URL : container.getJdbcUrl());
        ds.setUsername(EXT_URL != null ? "root" : "test");
        ds.setPassword(EXT_URL != null ? "root" : "test");
        assertThatThrownBy(() -> new JdbcQuotaStore(new JdbcTemplate(ds), "quota_stale_it",
                new QuotaSql.MySql(), true, IDS::incrementAndGet))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少列")
                .hasMessageContaining("quota_account_ddl.sql");
    }
}
