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
package com.xp.starsite.tuner.store.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * MyBatis 治理存储全链路测试（H2 内存库 + 手工装配——家族同形）：
 * upsert 幂等 / 窗口 CRUD / 审计往返 / versionStamp 写后必变 / 表前缀白名单。
 * H2 不支持 ON DUPLICATE KEY——本测试用例覆盖 H2 会炸的 upsert 语句，
 * 故 upsert 语义由 JDBC IT（真 MySQL）与 memory 行为测试承担，此处锁 CRUD 与映射。
 *
 * @author muyi
 * @date 2026/11/14
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MyBatisTunerStoreTest {

    /** H2 数据源 */
    private static DataSource ds;

    /** Mapper 代理 */
    private static TunerMapper mapper;

    /** 测试发号 */
    private static final AtomicLong IDS = new AtomicLong(11000);

    /** 建会话与 Mapper 代理 */
    @BeforeAll
    void setUp() {
        ds = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("tuner-mb-test").build();
        Environment env = new Environment("test", new JdbcTransactionFactory(), ds);
        Configuration cfg = new Configuration(env);
        cfg.addMapper(TunerMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);
        mapper = factory.openSession(true).getMapper(TunerMapper.class);
    }

    /** H2 建表通道（同构无索引——家族手法） */
    private static void createTables() throws Exception {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS tuner_preset ("
                    + " id BIGINT NOT NULL PRIMARY KEY,"
                    + " preset_name VARCHAR(64) NOT NULL,"
                    + " core_size INT NOT NULL, max_size INT NOT NULL, keep_alive_seconds INT NOT NULL, queue_capacity INT,"
                    + " gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + " gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            st.execute("CREATE TABLE IF NOT EXISTS tuner_window ("
                    + " id BIGINT NOT NULL PRIMARY KEY,"
                    + " pool_name VARCHAR(128) NOT NULL, preset_name VARCHAR(64) NOT NULL,"
                    + " start_cron VARCHAR(64) NOT NULL, end_cron VARCHAR(64) NOT NULL,"
                    + " gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + " gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            st.execute("CREATE TABLE IF NOT EXISTS tuner_binding ("
                    + " id BIGINT NOT NULL PRIMARY KEY,"
                    + " pool_name VARCHAR(128) NOT NULL, preset_name VARCHAR(64) NOT NULL,"
                    + " gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + " gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            st.execute("CREATE TABLE IF NOT EXISTS tuner_audit ("
                    + " id BIGINT NOT NULL PRIMARY KEY, occurred_at TIMESTAMP NOT NULL,"
                    + " host VARCHAR(128) NOT NULL, pool_name VARCHAR(128) NOT NULL,"
                    + " preset_name VARCHAR(64) NOT NULL, source VARCHAR(16) NOT NULL,"
                    + " old_core INT NOT NULL, old_max INT NOT NULL, old_keep INT NOT NULL,"
                    + " new_core INT NOT NULL, new_max INT NOT NULL, new_keep INT NOT NULL, old_queue INT, new_queue INT,"
                    + " gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + " gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    /** CRUD 往返 + 构造器映射 + versionStamp */
    @Test
    void crudRoundTripOnH2() throws Exception {
        createTables();
        MyBatisTunerStore store = new MyBatisTunerStore(mapper, "tuner", false,
                IDS::incrementAndGet, LocalDateTime::now);

        // 窗口新增（id 发号）+ 更新 + 清单
        com.xp.starsite.tuner.api.TunerWindow saved = store.upsertWindow(new com.xp.starsite.tuner.api.TunerWindow(
                "pool-a", "daily", "0 0 0 10 * ?", "0 0 0 16 * ?"));
        assertThat(saved.getId()).isPositive();
        com.xp.starsite.tuner.api.TunerWindow updated = store.upsertWindow(new com.xp.starsite.tuner.api.TunerWindow(
                saved.getId(), "pool-a", "repayday", "0 0 0 10 * ?", "0 0 0 16 * ?"));
        assertThat(store.windows("pool-a")).hasSize(1);
        assertThat(store.windows("pool-a").get(0).getPresetName()).isEqualTo("repayday");
        assertThat(store.windows(null)).hasSize(1);
        assertThat(store.windows("other")).isEmpty();
        store.removeWindow(updated.getId());
        assertThat(store.windows("pool-a")).isEmpty();

        // 审计往返（构造器映射 13 参）
        store.appendAudit(new com.xp.starsite.tuner.api.TunerAuditEntry(null,
                LocalDateTime.of(2026, 11, 14, 10, 0), "h:1", "pool-a", "repayday",
                com.xp.starsite.tuner.api.TunerAuditEntry.Source.MANUAL,
                2, 4, 60, 8, 16, 60));
        List<com.xp.starsite.tuner.api.TunerAuditEntry> audits = store.recentAudits(10);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getNewCore()).isEqualTo(8);
        assertThat(audits.get(0).getSource())
                .isEqualTo(com.xp.starsite.tuner.api.TunerAuditEntry.Source.MANUAL);

        // versionStamp：审计写入后非零（写后必变契约）
        assertThat(store.versionStamp()).isPositive();
        assertThat(store.countBindings()).isZero();
    }

    /** 表前缀白名单 */
    @Test
    void tablePrefixWhitelist() {
        assertThatThrownBy(() -> new MyBatisTunerStore(mapper, "bad-prefix", false,
                IDS::incrementAndGet, LocalDateTime::now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("字母数字下划线");
    }
}
