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
package com.xp.starsite.quota.store.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
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
 * MyBatis 适配全链路测试（H2 内存库 + 手工 MyBatis 装配——家族同形）：
 * 建户幂等 / 五原子操作往返与条件拒绝 / 降额保护 / 表名白名单 / 陈旧结构 fail-fast。
 *
 * @author muyi
 * @date 2026/11/14
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MyBatisQuotaStoreTest {

    /** H2 数据源（类级共享） */
    private static DataSource ds;

    /** Mapper 代理 */
    private static QuotaAccountMapper mapper;

    /** 测试发号 */
    private static final AtomicLong IDS = new AtomicLong(7000);

    /** 建会话与 Mapper 代理 */
    @BeforeAll
    void setUp() {
        ds = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("quota-mb-test").build();
        Environment env = new Environment("test", new JdbcTransactionFactory(), ds);
        Configuration cfg = new Configuration(env);
        cfg.addMapper(QuotaAccountMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);
        mapper = factory.openSession(true).getMapper(QuotaAccountMapper.class);
    }

    /** H2 建表通道：同构（MySQL KEY 内联语法 H2 不支持——家族既有手法） */
    private static void createH2Table(String table) throws Exception {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + " id BIGINT NOT NULL PRIMARY KEY,"
                    + " quota_key VARCHAR(128) NOT NULL,"
                    + " total_amount BIGINT NOT NULL,"
                    + " occupied BIGINT NOT NULL DEFAULT 0,"
                    + " frozen BIGINT NOT NULL DEFAULT 0,"
                    + " gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + " gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + " CONSTRAINT uk_test UNIQUE (quota_key))");
        }
    }

    /** 全链路往返：建户幂等 + 五操作 + 降额保护 */
    @Test
    void fullRoundTripOnH2() throws Exception {
        createH2Table("quota_account_h2");
        MyBatisQuotaStore store = new MyBatisQuotaStore(mapper, "quota_account_h2",
                false, IDS::incrementAndGet, LocalDateTime::now);

        // 建户 + 幂等（重复建户保留现量）
        assertThat(store.init("credit:A", 1000).getAvailable()).isEqualTo(1000);
        store.occupy("credit:A", 200);
        assertThat(store.init("credit:A", 9999).getTotal()).isEqualTo(1000);

        // 占用/冻结共享池 + 恰好边界（200+800=1000 恰好命中不变式）
        assertThat(store.occupy("credit:A", 800).get().getOccupied()).isEqualTo(1000);
        assertThat(store.freeze("credit:A", 1)).isEmpty(); // 已满——1 也冻结不动

        // 冻结预占（先释放腾出池）+ 转销换位
        assertThat(store.release("credit:A", 400).get().getOccupied()).isEqualTo(600);
        assertThat(store.freeze("credit:A", 400).get().getFrozen()).isEqualTo(400);
        assertThat(store.freeze("credit:A", 1)).isEmpty(); // 池满
        assertThat(store.confirm("credit:A", 400).get().getOccupied()).isEqualTo(1000);
        assertThat(store.confirm("credit:A", 1)).isEmpty(); // 冻结已空

        // 解冻充足性拒绝（无冻结可解）
        assertThat(store.unfreeze("credit:A", 1)).isEmpty();

        // 释放 + 充足性拒绝
        assertThat(store.release("credit:A", 1000).get().getOccupied()).isZero();
        assertThat(store.release("credit:A", 1)).isEmpty();

        // 降额保护（当前 0 占用）
        assertThat(store.adjustTotal("credit:A", -500).get().getTotal()).isEqualTo(500);
        store.occupy("credit:A", 500);
        assertThat(store.adjustTotal("credit:A", -1)).isEmpty(); // 500-1 < 500 占用

        assertThat(store.countAccounts()).isEqualTo(1);
        assertThat(store.find("credit:A")).isPresent();
        assertThat(store.find("absent")).isEmpty();
    }

    /** 表名白名单 */
    @Test
    void tableNameWhitelist() {
        assertThatThrownBy(() -> new MyBatisQuotaStore(mapper, "bad-name", false,
                IDS::incrementAndGet))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("字母数字下划线");
    }

    /** 陈旧结构 fail-fast（BUG-009）：表存在但缺契约列 */
    @Test
    void staleSchemaFailsFast() throws Exception {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS quota_stale (id BIGINT NOT NULL PRIMARY KEY)");
        }
        assertThatThrownBy(() -> new MyBatisQuotaStore(mapper, "quota_stale", true,
                IDS::incrementAndGet))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少列")
                .hasMessageContaining("quota_account_ddl.sql");
    }

    /** 表不存在：只告警不阻断（DBA/迁移脚本管理形态） */
    @Test
    void absentTableSkipsVerification() {
        MyBatisQuotaStore store = new MyBatisQuotaStore(mapper, "quota_absent", true,
                IDS::incrementAndGet);
        assertThat(store).isNotNull();
    }
}
