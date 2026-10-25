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
package com.xp.starsite.sequence.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MyBatis 版 workerId 分配器全链路测试（H2 MySQL 兼容模式——
 * ON DUPLICATE KEY / INSERT IGNORE / LAST_INSERT_ID(expr) 三件套与生产 MySQL 同语义）。
 * 覆盖：冷启动建表播种、连续取号递增、跨服务共享全局池不撞号、审计行 upsert 幂等、回绕重置。
 *
 * @author muyi
 * @date 2026/10/25
 */
class MyBatisMysqlWorkerIdAllocatorTest {

    /** H2 数据源（MySQL 兼容模式；DB_CLOSE_DELAY 保持库存活） */
    private static DataSource dataSource;

    /** 手工装配的 SqlSessionFactory */
    private static SqlSessionFactory sqlSessionFactory;

    /** 测试 id 生成序列（审计行主键） */
    private static final AtomicLong TEST_ID_SEQ = new AtomicLong(100);

    /**
     * 建库（每用例先 DROP 再由分配器自建——覆盖"冷启动建表"路径）。
     */
    @BeforeAll
    static void setup() throws Exception {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:seqmybatis;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource = ds;
        Configuration config = new Configuration();
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        config.addMapper(SequenceMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(config);
    }

    /**
     * 构造分配器（事务管理器为直通桩——H2 内存库无跨连接可见性要求，事务语义由 H2 自身保证）。
     *
     * @param table    本用例表名
     * @param service  服务名（审计行 key）
     * @param idBits   机器位宽度
     * @return 分配器实例
     */
    private static MyBatisMysqlWorkerIdAllocator allocator(String table, String service, int idBits) {
        SqlSession session = sqlSessionFactory.openSession(true);
        SequenceMapper mapper = session.getMapper(SequenceMapper.class);
        SqlSessionTemplate template = new SqlSessionTemplate(sqlSessionFactory);
        // 直通事务管理器：H2 单连接 autocommit 场景无需真实事务边界（取号两步的原子性靠 UPDATE 单语句）
        TransactionTemplate txTemplate = new TransactionTemplate(new PassThroughTxManager());
        return new MyBatisMysqlWorkerIdAllocator(mapper, template, txTemplate,
                table, service, service + "@tag", idBits, "RESET",
                TEST_ID_SEQ::incrementAndGet);
    }

    /** 清表（幂等） */
    private static void dropTable(String table) throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    /** 冷启动：建表 + 播种 + 首取 1 次取 2，审计行记录最新占用 */
    @Test
    void 冷启动_建表播种_连续取号递增() throws Exception {
        dropTable("sys_seq_m1");
        MyBatisMysqlWorkerIdAllocator allocator = allocator("sys_seq_m1", "svc-a", 10);

        assertThat(allocator.allocate()).isEqualTo(1);
        assertThat(allocator.allocate()).isEqualTo(2);

        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement();
             var rs = st.executeQuery(
                     "SELECT worker_id, instance FROM sys_seq_m1 WHERE service_name='svc-a'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(2);       // 审计行跟随最新占用
            assertThat(rs.getString(2)).isEqualTo("svc-a@tag");
        }
    }

    /** 跨服务共享全局池：svc-b 从 svc-a 已取到的 2 之后继续——全平台不撞号 */
    @Test
    void 跨服务_共享全局池不撞号() throws Exception {
        dropTable("sys_seq_m2");
        allocator("sys_seq_m2", "svc-a", 10).allocate();
        allocator("sys_seq_m2", "svc-a", 10).allocate();

        assertThat(allocator("sys_seq_m2", "svc-b", 10).allocate()).isEqualTo(3);
    }

    /** 审计行 upsert 幂等：同服务重启重复分配不新增行（uk_service_name 锚定覆盖更新） */
    @Test
    void 审计行_upsert幂等不膨胀() throws Exception {
        dropTable("sys_seq_m3");
        MyBatisMysqlWorkerIdAllocator allocator = allocator("sys_seq_m3", "svc-a", 10);
        allocator.allocate();
        allocator.allocate();

        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement();
             var rs = st.executeQuery(
                     "SELECT COUNT(*) FROM sys_seq_m3 WHERE service_name='svc-a'")) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(1); // __global__ 1 行 + svc-a 恒 1 行
        }
    }

    /** 回绕 RESET：池计数超机器位上限（2 位宽 = 上限 3）后重置重取 */
    @Test
    void 回绕RESET_超上限重置重取() throws Exception {
        dropTable("sys_seq_m4");
        MyBatisMysqlWorkerIdAllocator allocator = allocator("sys_seq_m4", "svc-a", 2); // 上限 3

        assertThat(allocator.allocate()).isEqualTo(1);
        assertThat(allocator.allocate()).isEqualTo(2);
        assertThat(allocator.allocate()).isEqualTo(3);
        assertThat(allocator.allocate()).isEqualTo(1); // 回绕：池重置后从 1 重取
    }

    /** 直通事务管理器（H2 autocommit 单语句原子，无需真实边界） */
    static class PassThroughTxManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            // 直通
        }

        @Override
        public void rollback(TransactionStatus status) {
            // 直通
        }
    }
}
