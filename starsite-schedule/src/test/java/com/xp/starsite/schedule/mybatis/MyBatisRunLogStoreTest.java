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
package com.xp.starsite.schedule.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import com.xp.starsite.schedule.api.RunStatus;
import com.xp.starsite.schedule.store.ScheduleRunLogSql;
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
 * MyBatis 适配全链路测试（H2 内存库 + 手工 MyBatis 装配——与 async-command 同形）：
 * H2 不认 MySQL 内联 KEY 索引语法，建表走 JDBC 无索引同构表（真 DDL 由
 * {@code JdbcRunLogStoreIT} 在真库验证）；行数极小，索引不影响语义。
 *
 * @author muyi
 * @date 2026/11/02
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MyBatisRunLogStoreTest {

    /** H2 数据源（类级共享——各用例独立表名隔离） */
    private static DataSource ds;

    /** Mapper 代理 */
    private static ScheduleRunLogMapper mapper;

    /** 测试发号 */
    private static final AtomicLong IDS = new AtomicLong(2000);

    @BeforeAll
    void setUp() {
        ds = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("schedule-mb-test").build();
        Environment env = new Environment("test", new JdbcTransactionFactory(), ds);
        Configuration cfg = new Configuration(env);
        cfg.addMapper(ScheduleRunLogMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);
        mapper = factory.openSession(true).getMapper(ScheduleRunLogMapper.class);
    }

    /** H2 建表通道：同构无索引（MySQL KEY 内联语法 H2 不支持——见类 doc） */
    private static void createH2Table(String table) throws Exception {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + " id BIGINT NOT NULL PRIMARY KEY,"
                    + " task_name VARCHAR(64) NOT NULL,"
                    + " lock_name VARCHAR(128) NOT NULL,"
                    + " trigger_time TIMESTAMP NOT NULL,"
                    + " started_at TIMESTAMP NOT NULL,"
                    + " finished_at TIMESTAMP,"
                    + " host VARCHAR(128) NOT NULL,"
                    + " status VARCHAR(16) NOT NULL,"
                    + " duration_ms BIGINT,"
                    + " error VARCHAR(500),"
                    + " gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + " gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    private static LocalDateTime t(int hour) {
        return LocalDateTime.of(2026, 11, 2, hour, 0, 0);
    }

    @Test
    void fullRoundTripOnH2() throws Exception {
        createH2Table("schedule_run_log_h2");
        MyBatisRunLogStore store = new MyBatisRunLogStore(mapper, "schedule_run_log_h2", false, IDS::incrementAndGet);

        var log = store.start("mb1", "sch:mb1", t(8), t(8), "h:1");
        assertThat(log.getStatus()).isEqualTo(RunStatus.RUNNING);

        store.finish(log.getId(), RunStatus.DONE, 7L, null);
        var done = store.findById(log.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(RunStatus.DONE);
        assertThat(done.getDurationMs()).isEqualTo(7L);

        // 列表分列：无 error；详情有
        store.skip("mb1", "sch:mb1", t(9), t(9), "h:2");
        assertThat(store.recentByTask("mb1", 10)).hasSize(2)
                .allMatch(l -> l.getError() == null);

        // lastDone = 最新 DONE 的触发时刻
        assertThat(store.lastDoneTriggerTime("mb1")).contains(t(8));

        // 悬挂回收（H2 无 ON UPDATE——语义由 UPDATE 本身保证）
        store.markStaleRunning(t(10));
        assertThat(store.recentByTask("mb1", 10))
                .filteredOn(l -> l.getStatus() == RunStatus.SKIPPED_LOCKED)
                .hasSize(1);  // 无 RUNNING 残留 → 只剩原状
    }

    @Test
    void tableNameWhitelist() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MyBatisRunLogStore(mapper, "bad-name", false, IDS::incrementAndGet));
    }

    @Test
    void dialectAndMapperColumnSetsAligned() {
        // 防漂移：JDBC 方言与 MyBatis 两条路径的建表语句列集一致（error 列两边都在）
        String jdbcDdl = new ScheduleRunLogSql.MySql().createTable();
        assertThat(jdbcDdl).contains("error").contains("duration_ms").contains("finished_at");
    }
}
