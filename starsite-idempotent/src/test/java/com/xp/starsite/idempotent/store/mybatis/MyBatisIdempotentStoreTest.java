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
package com.xp.starsite.idempotent.store.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xp.starsite.idempotent.api.IdempotentStatus;
import com.xp.starsite.idempotent.clock.TimeSource;
import com.xp.starsite.idempotent.store.ClaimOutcome;
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
 * MyBatis 适配全链路测试（H2 内存库 + 手工 MyBatis 装配——与 async-command/schedule 同形）：
 * H2 不认 MySQL 内联 KEY 索引语法，建表走 JDBC 无索引同构表（真 DDL 由
 * {@code JdbcIdempotentStoreIT} 在真库验证）；行数极小，索引不影响语义。
 *
 * @author muyi
 * @date 2026/11/12
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MyBatisIdempotentStoreTest {

    /** H2 数据源（类级共享——各用例独立表名隔离） */
    private static DataSource ds;

    /** Mapper 代理 */
    private static IdempotentRecordMapper mapper;

    /** 测试发号 */
    private static final AtomicLong IDS = new AtomicLong(3000);

    /** 基准时刻（回收/过期判定的锚） */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 10, 0, 0);

    /** 系统时间源（过期判定用参数时刻——与调用方时钟一致） */
    private static final TimeSource TIME = () -> NOW;

    /** 建会话与 Mapper 代理 */
    @BeforeAll
    void setUp() {
        ds = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("idempotent-mb-test").build();
        Environment env = new Environment("test", new JdbcTransactionFactory(), ds);
        Configuration cfg = new Configuration(env);
        cfg.addMapper(IdempotentRecordMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);
        mapper = factory.openSession(true).getMapper(IdempotentRecordMapper.class);
    }

    /** H2 建表通道：同构无索引（MySQL KEY 内联语法 H2 不支持——见类 doc） */
    private static void createH2Table(String table) throws Exception {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + " id BIGINT NOT NULL PRIMARY KEY,"
                    + " scope VARCHAR(64) NOT NULL,"
                    + " idem_key VARCHAR(128) NOT NULL,"
                    + " status VARCHAR(16) NOT NULL,"
                    + " result TEXT,"
                    + " error VARCHAR(500),"
                    + " expire_at TIMESTAMP NOT NULL,"
                    + " finished_at TIMESTAMP,"
                    + " host VARCHAR(128) NOT NULL,"
                    + " gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + " gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + " CONSTRAINT uk_test UNIQUE (scope, idem_key))");
        }
    }

    /** 全链路往返：认领 → duplicate → complete（守卫）→ 回读 DONE 载荷 → 回收 → 清理 */
    @Test
    void fullRoundTripOnH2() throws Exception {
        createH2Table("idempotent_record_h2");
        MyBatisIdempotentStore store = new MyBatisIdempotentStore(mapper,
                "idempotent_record_h2", false, IDS::incrementAndGet, TIME);

        // 认领 → duplicate（uk 冲突经回查翻译）
        LocalDateTime expire = NOW.plusHours(24);
        ClaimOutcome first = store.tryClaim("order", "o-1", expire, "h:1");
        assertThat(first.claimed()).isTrue();
        assertThat(store.tryClaim("order", "o-1", expire, "h:2").claimed()).isFalse();

        // 终态（本代令牌）+ 详情回读
        assertThat(store.complete("order", "o-1", expire, "{\"amount\":100}")).isTrue();
        var done = store.find("order", "o-1").orElseThrow();
        assertThat(done.getStatus()).isEqualTo(IdempotentStatus.DONE);
        assertThat(done.getResult()).contains("100");
        assertThat(done.getGmtCreate()).isNotNull();

        // 旧代守卫拒绝（代际令牌不符）
        assertThat(store.complete("order", "o-1", expire.plusHours(1), "ghost")).isFalse();

        // FAILED 释放键 → 回收重置 PROCESSING
        ClaimOutcome f2 = store.tryClaim("order", "o-2", expire, "h:1");
        assertThat(store.fail("order", "o-2", expire, "boom")).isTrue();
        var reclaimed = store.tryReclaim("order", "o-2", expire, NOW, NOW.minusSeconds(120), "h:3");
        assertThat(reclaimed).isPresent();
        assertThat(reclaimed.orElseThrow().getStatus()).isEqualTo(IdempotentStatus.PROCESSING);

        // 状态计数
        assertThat(store.countByStatus(IdempotentStatus.DONE)).isEqualTo(1);

        // 两步清理：expire_at 过期行删除
        assertThat(store.purgeExpired(10, NOW.plusHours(25))).isEqualTo(2);
        assertThat(store.countByStatus(IdempotentStatus.DONE)).isZero();
    }

    /** 表名白名单 */
    @Test
    void tableNameWhitelist() {
        assertThatThrownBy(() -> new MyBatisIdempotentStore(mapper, "bad-name", false,
                IDS::incrementAndGet))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("字母数字下划线");
    }

    /** 结构校验（BUG-009）：表存在但缺契约列 → 启动期快速失败含迁移指引 */
    @Test
    void staleSchemaFailsFast() throws Exception {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS idempotent_stale ("
                    + " id BIGINT NOT NULL PRIMARY KEY,"
                    + " idem_key VARCHAR(128) NOT NULL)"); // 陈旧结构：缺 9 列
        }
        assertThatThrownBy(() -> new MyBatisIdempotentStore(mapper, "idempotent_stale", true,
                IDS::incrementAndGet))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少列")
                .hasMessageContaining("idempotent_record_ddl.sql");
    }

    /** 结构校验：表不存在 → 只告警不阻断（DBA/迁移脚本管理形态——构造即断言，不查缺席表） */
    @Test
    void absentTableSkipsVerification() {
        MyBatisIdempotentStore store = new MyBatisIdempotentStore(mapper, "idempotent_absent",
                true, IDS::incrementAndGet);
        assertThat(store).isNotNull(); // 构造未抛 = 校验放行（查询等真实表存在后由 DBA DDL 兜底）
    }
}
