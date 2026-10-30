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
package com.xp.starsite.asynccmd.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import com.xp.starsite.asynccmd.client.AsyncCommandClient;
import com.xp.starsite.asynccmd.config.AsyncCommandConfig;
import com.xp.starsite.asynccmd.executor.AsyncCommandExecutor;
import com.xp.starsite.asynccmd.executor.ExecutorRegistry;
import com.xp.starsite.asynccmd.model.AsyncCommand;
import com.xp.starsite.asynccmd.model.CommandStatus;
import com.xp.starsite.asynccmd.scheduler.AsyncCommandScheduler;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * MyBatis 适配全链路测试（H2 内存库 + 手工 MyBatis 装配）：
 * Mapper SQL 与 Store 语义在 MyBatis 执行路径下端到端验证——建表/提交/认领/回收/退避/分页。
 * 不用 @MybatisTest——手工 SqlSessionFactoryBean + MapperFactoryBean 装配，
 * 只依赖 mybatis-spring（provided 已有）不依赖 test-spring 专用 artifact。
 *
 * @author muyi
 * @date 2026/10/25
 */
class MyBatisAsyncCommandStoreTest {

    /** H2 数据源（类级共享——各用例用独立表名隔离） */
    private static DataSource dataSource;

    /** MyBatis SqlSessionFactory（手工装配） */
    private static SqlSessionFactory sqlSessionFactory;

    /** 测试 id 生成序列（模拟 sequence 组件发号——物理主键来源语义等价） */
    private static final AtomicLong TEST_ID_SEQ =
            new AtomicLong(0);

    /**
     * 建库与 MyBatis 装配：EmbeddedH2 → SqlSessionFactory（驼峰映射开启）→ 注册 Mapper。
     */
    @BeforeAll
    static void setupMyBatis() throws Exception {
        dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .build();
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        org.apache.ibatis.session.Configuration ibatisConfig =
                new org.apache.ibatis.session.Configuration();
        ibatisConfig.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(ibatisConfig);
        sqlSessionFactory = factoryBean.getObject();
        sqlSessionFactory.getConfiguration().addMapper(AsyncCommandMapper.class);
    }

    /**
     * 创建 Mapper 代理（MapperFactoryBean 核心逻辑，每测试方法独立获取）。
     *
     * @return MyBatis Mapper 代理
     */
    private static AsyncCommandMapper mapper() throws Exception {
        MapperFactoryBean<AsyncCommandMapper> factory = new MapperFactoryBean<>(AsyncCommandMapper.class);
        factory.setSqlSessionFactory(sqlSessionFactory);
        factory.afterPropertiesSet();
        return factory.getObject();
    }

    /**
     * H2 建表通道：createTable 语句是 MySQL 方言（内联 KEY 索引语法 H2 不支持，
     * 由真库 IT 验证）；H2 单测经 JDBC 建同构无索引表——行数极小，索引不影响语义。
     *
     * @param table 本用例专属表名
     */
    private static void createH2Table(String table) throws Exception {
        try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "id BIGINT NOT NULL PRIMARY KEY, "
                    + "cmd_type VARCHAR(64) NOT NULL, "
                    + "biz_key VARCHAR(128) DEFAULT '', "
                    + "payload TEXT, "
                    + "status VARCHAR(16) NOT NULL DEFAULT 'PENDING', "
                    + "max_retry INT NOT NULL DEFAULT 5, "
                    + "retry_count INT NOT NULL DEFAULT 0, "
                    + "next_run_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "last_error VARCHAR(500), "
                    + "owner VARCHAR(64) DEFAULT '', "
                    + "claim_token VARCHAR(64), "
                    + "gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    /** 全链路：提交 → 认领 → 执行 → SUCCESS（SQL 全部经 MyBatis 执行） */
    @Test
    void 提交认领执行_全链路经MyBatis() throws Exception {
        AsyncCommandMapper mapper = mapper();
        createH2Table("async_cmd_t1");
        MyBatisAsyncCommandStore store = new MyBatisAsyncCommandStore(mapper, "async_cmd_t1", false, TEST_ID_SEQ::incrementAndGet);

        AsyncCommandConfig config = new AsyncCommandConfig();
        config.setOwner("mybatis-test");
        AsyncCommandClient client = new AsyncCommandClient(store, config);
        RecordingExecutor exec = new RecordingExecutor("sync");
        AsyncCommandScheduler scheduler = new AsyncCommandScheduler(store, client, config,
                new ExecutorRegistry(() -> List.of(exec)));

        AsyncCommand cmd = client.submit("sync", "k1", "{}");
        assertThat(cmd.getId()).isNotNull();
        assertThat(cmd.getStatusEnum()).isEqualTo(CommandStatus.PENDING);

        scheduler.tick();
        assertThat(exec.executed).hasSize(1);
        assertThat(store.countByStatus("SUCCESS")).isEqualTo(1);
    }

    /** 双"实例"令牌认领互斥 */
    @Test
    void 双实例认领互斥() throws Exception {
        AsyncCommandMapper mapper = mapper();
        createH2Table("async_cmd_t2");
        MyBatisAsyncCommandStore store = new MyBatisAsyncCommandStore(mapper, "async_cmd_t2", false, TEST_ID_SEQ::incrementAndGet);
        AsyncCommandConfig config = new AsyncCommandConfig();
        config.setOwner("t");
        AsyncCommandClient client = new AsyncCommandClient(store, config);
        for (int i = 0; i < 4; i++) {
            client.submit("race", "k" + i, "{}");
        }
        assertThat(store.claim("inst-a", 2, LocalDateTime.now())).isEqualTo(2);
        assertThat(store.claim("inst-b", 2, LocalDateTime.now())).isEqualTo(2);
        List<Long> idsA = store.findByToken("inst-a").stream().map(AsyncCommand::getId).toList();
        List<Long> idsB = store.findByToken("inst-b").stream().map(AsyncCommand::getId).toList();
        assertThat(idsA).doesNotContainAnyElementsOf(idsB);
    }

    /** 悬挂回收：RUNNING 超时回 PENDING */
    @Test
    void 悬挂回收() throws Exception {
        AsyncCommandMapper mapper = mapper();
        createH2Table("async_cmd_t3");
        MyBatisAsyncCommandStore store = new MyBatisAsyncCommandStore(mapper, "async_cmd_t3", false, TEST_ID_SEQ::incrementAndGet);
        AsyncCommandConfig config = new AsyncCommandConfig();
        config.setOwner("t");
        AsyncCommandClient client = new AsyncCommandClient(store, config);
        AsyncCommand cmd = client.submit("stale", "k1", "{}");
        assertThat(store.claimById(cmd.getId(), "dead")).isTrue();
        assertThat(store.countByStatus("RUNNING")).isEqualTo(1);
        assertThat(store.reclaimStale(0, 10)).isEqualTo(1);
        assertThat(store.countByStatus("PENDING")).isEqualTo(1);
    }

    /** 管控分页过滤 */
    @Test
    void 管控分页过滤() throws Exception {
        AsyncCommandMapper mapper = mapper();
        createH2Table("async_cmd_t4");
        MyBatisAsyncCommandStore store = new MyBatisAsyncCommandStore(mapper, "async_cmd_t4", false, TEST_ID_SEQ::incrementAndGet);
        AsyncCommandConfig config = new AsyncCommandConfig();
        config.setOwner("t");
        AsyncCommandClient client = new AsyncCommandClient(store, config);
        client.submit("page", "k1", "{}");
        client.submit("page", "k2", "{}");
        client.submit("other", "k3", "{}");

        assertThat(store.countPage(null, null)).isEqualTo(3);
        assertThat(store.countPage("PENDING", "page")).isEqualTo(2);
        assertThat(store.selectPage(null, "other", 0, 10)).hasSize(1);

        // 列表/详情分列：列表不携带 payload 大列（显式列清单），详情按 id 取全量
        assertThat(store.selectPage(null, null, 0, 10))
                .allSatisfy(c -> assertThat(c.getPayload()).isNull());
        assertThat(store.selectRecent(10))
                .allSatisfy(c -> assertThat(c.getPayload()).isNull());
        AsyncCommand first = store.selectPage(null, "page", 0, 1).get(0);
        AsyncCommand detail = store.findById(first.getId());
        assertThat(detail.getPayload()).isEqualTo("{}");
    }

    /** 记录执行的假执行器 */
    static class RecordingExecutor implements AsyncCommandExecutor {
        final String typeName;
        final List<Long> executed = new CopyOnWriteArrayList<>();

        RecordingExecutor(String typeName) { this.typeName = typeName; }

        @Override public String type() { return typeName; }
        @Override public void execute(AsyncCommand cmd) { executed.add(cmd.getId()); }
    }
}
