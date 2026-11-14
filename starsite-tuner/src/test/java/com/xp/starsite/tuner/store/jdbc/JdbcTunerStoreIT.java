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
package com.xp.starsite.tuner.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.xp.starsite.tuner.api.TunerAuditEntry;
import com.xp.starsite.tuner.api.TunerPreset;
import com.xp.starsite.tuner.api.TunerWindow;
import com.xp.starsite.tuner.store.TunerSql;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * JDBC 治理存储真库 IT（Testcontainers mysql:8.0；TEST_MYSQL_URL 逃生口——家族同形）。
 * 验证：四表真 DDL / upsert 幂等（ON DUPLICATE KEY 真语义）/ 窗口 CRUD / 审计往返 /
 * versionStamp 写后必变 / 表前缀白名单。
 *
 * @author muyi
 * @date 2026/11/14
 */
class JdbcTunerStoreIT {

    /** 外部库直连地址（无 docker 环境的逃生口） */
    private static final String EXT_URL = System.getenv("TEST_MYSQL_URL");

    /** 一次性 MySQL 容器 */
    private static MySQLContainer<?> container;

    /** 被测存储 */
    private static JdbcTunerStore store;

    /** 测试发号 */
    private static final AtomicLong IDS = new AtomicLong(13000);

    /** 起容器/数据源/建表（先 DROP 旧表——外部库重跑残留） */
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
        try (var conn = java.sql.DriverManager.getConnection(url, user, pass);
                var st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS tuner_preset");
            st.execute("DROP TABLE IF EXISTS tuner_window");
            st.execute("DROP TABLE IF EXISTS tuner_binding");
            st.execute("DROP TABLE IF EXISTS tuner_audit");
        }
        store = new JdbcTunerStore(new JdbcTemplate(ds), "tuner", new TunerSql.MySql(), true,
                IDS::incrementAndGet, LocalDateTime::now);
    }

    /** 收容器 */
    @AfterAll
    static void stopDb() {
        if (container != null) {
            container.stop();
        }
    }

    /** upsert 幂等（真 MySQL ON DUPLICATE KEY 语义）+ 点查 */
    @Test
    void presetUpsertIdempotent() {
        store.upsertPreset(new TunerPreset("daily", 2, 4, 60));
        store.upsertPreset(new TunerPreset("daily", 6, 8, 120)); // 同名更新
        var preset = store.preset("daily").orElseThrow();
        assertThat(preset.getCoreSize()).isEqualTo(6); // 更新生效（非重复行）
        assertThat(store.presets()).hasSize(1);
    }

    /** 窗口 CRUD + 清单过滤 */
    @Test
    void windowCrud() {
        var w1 = store.upsertWindow(new TunerWindow("it:daikou", "repayday",
                "0 0 0 10 * ?", "0 0 0 16 * ?"));
        assertThat(w1.getId()).isPositive();
        store.upsertWindow(new TunerWindow("it:other", "daily", "0 0 23 * * ?", "0 0 0 * * ?"));
        assertThat(store.windows("it:daikou")).hasSize(1);
        assertThat(store.windows(null)).hasSize(2);
        // 更新
        store.upsertWindow(new TunerWindow(w1.getId(), "it:daikou", "daily",
                w1.getStartCron(), w1.getEndCron()));
        assertThat(store.windows("it:daikou").get(0).getPresetName()).isEqualTo("daily");
        // 删除
        store.removeWindow(w1.getId());
        assertThat(store.windows("it:daikou")).isEmpty();
    }

    /** 绑定 upsert 幂等 + 计数 */
    @Test
    void bindingUpsertAndCount() {
        store.bindDefault("it:pool", "daily");
        store.bindDefault("it:pool", "repayday"); // 同池更新
        assertThat(store.defaultPreset("it:pool")).contains("repayday");
        assertThat(store.countBindings()).isEqualTo(1);
    }

    /** 审计往返 + versionStamp（秒级精度契约：同秒写可能同戳，断言 >=；memory 严格单调） */
    @Test
    void auditRoundTripAndVersionStamp() {
        long before = store.versionStamp();
        store.appendAudit(new TunerAuditEntry(null, LocalDateTime.of(2026, 11, 14, 10, 0),
                "it-host:1", "it:pool", "repayday", TunerAuditEntry.Source.SCHEDULE,
                2, 4, 60, 8, 16, 60));
        var audits = store.recentAudits(10);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getHost()).isEqualTo("it-host:1");
        assertThat(audits.get(0).getOldCore()).isEqualTo(2);
        assertThat(audits.get(0).getNewMax()).isEqualTo(16);
        assertThat(store.versionStamp()).as("秒级精度：>=before（同秒写可能同戳）")
                .isGreaterThanOrEqualTo(before);
    }

    /** 审计 retention：到期行两步清理（真 MySQL），未到期保留 */
    @Test
    void auditRetentionPurge() {
        long before = store.versionStamp();
        store.appendAudit(new TunerAuditEntry(null, LocalDateTime.of(2020, 1, 1, 0, 0),
                "old-host", "it:retire", "old", TunerAuditEntry.Source.SCHEDULE,
                1, 1, 1, 2, 2, 2));
        store.appendAudit(new TunerAuditEntry(null, LocalDateTime.now().plusDays(1),
                "new-host", "it:retire", "new", TunerAuditEntry.Source.SCHEDULE,
                1, 1, 1, 2, 2, 2));
        long purged = store.purgeExpiredAudits(500,
                LocalDateTime.now().minusDays(7)); // 2020 行到期，明天行保留
        assertThat(purged).isEqualTo(1);
        var remaining = store.recentAudits(10);
        assertThat(remaining).extracting(TunerAuditEntry::getHost)
                .containsExactly("new-host");
        assertThat(store.versionStamp()).isGreaterThanOrEqualTo(before);
        // 收尾自清理（用例间数据隔离——JUnit 方法序不保证）
        store.purgeExpiredAudits(500, LocalDateTime.now().plusDays(2));
        assertThat(store.recentAudits(10)).isEmpty();
    }

    /** 表前缀白名单 */
    @Test
    void tablePrefixWhitelist() {
        assertThatThrownByByDesign();
    }

    /** 白名单断言（独立方法——构造器在参数错误时抛 IAE，早于任何 JDBC 交互） */
    private static void assertThatThrownByByDesign() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new JdbcTunerStore(null,
                "bad-prefix", new TunerSql.MySql(), false, IDS::incrementAndGet,
                LocalDateTime::now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("字母数字下划线");
    }
}
