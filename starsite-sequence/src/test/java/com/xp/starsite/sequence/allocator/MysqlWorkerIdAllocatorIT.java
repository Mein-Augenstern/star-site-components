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
package com.xp.starsite.sequence.allocator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

/**
 * MySQL 租约分配器集成测试：真库验证 LAST_INSERT_ID 原子租约 trick。
 * <ul>
 *   <li>默认：Testcontainers 拉起一次性 mysql:8.0（需要 docker）</li>
 *   <li>设置 {@code TEST_MYSQL_URL}（配 TEST_MYSQL_USER/TEST_MYSQL_PASSWORD，默认 root/root）
 *       时直接用外部库——无 docker 环境也能跑 IT 的标准逃生口</li>
 * </ul>
 * 每个测试方法独立表名（全局池状态互不污染）。
 *
 * @author muyi
 * @date 2026/10/25
 */
class MysqlWorkerIdAllocatorIT {

    /** 外部库逃生口 URL（环境变量 TEST_MYSQL_URL）；null = 无外部库，走 Testcontainers 拉起一次性容器 */
    private static final String EXT_URL = System.getenv("TEST_MYSQL_URL");

    /** Testcontainers 拉起的一次性 mysql 容器；使用外部库时保持 null */
    private static MySQLContainer<?> container;

    /**
     * 类级准备数据库：无外部库时拉起一次性 mysql:8.0 容器（需要 docker）。
     */
    @BeforeAll
    static void startDb() {
        if (EXT_URL == null) {
            container = new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("starsite").withUsername("test").withPassword("test");
            container.start();
        }
    }

    /**
     * 类级收尾：停止容器（外部库模式无容器可停，直接跳过）。
     */
    @AfterAll
    static void stopDb() {
        if (container != null) {
            container.stop();
        }
    }

    /**
     * 取当前测试库的 JDBC URL。
     *
     * @return 外部库模式返回 TEST_MYSQL_URL；容器模式返回容器映射出的 JDBC URL
     */
    private static String jdbcUrl() {
        return EXT_URL != null ? EXT_URL : container.getJdbcUrl();
    }

    /**
     * 取当前测试库用户名。
     *
     * @return 外部库模式取环境变量 TEST_MYSQL_USER（默认 root）；容器模式取容器用户
     */
    private static String dbUser() {
        return EXT_URL != null ? env("TEST_MYSQL_USER", "root") : container.getUsername();
    }

    /**
     * 取当前测试库密码。
     *
     * @return 外部库模式取环境变量 TEST_MYSQL_PASSWORD（默认 root）；容器模式取容器密码
     */
    private static String dbPassword() {
        return EXT_URL != null ? env("TEST_MYSQL_PASSWORD", "root") : container.getPassword();
    }

    /**
     * 读环境变量并支持默认值兜底。
     *
     * @param key 环境变量名
     * @param def 环境变量未设置时的兜底值
     * @return 环境变量值；未设置时返回 def
     */
    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null ? def : v;
    }

    /**
     * 构造指向当前测试库的简单 DataSource（每次新建，测试规模下无需池化）。
     *
     * @return 已配置 URL/用户名/密码的 SimpleDriverDataSource
     * @throws SQLException 驱动实例化失败
     */
    private DataSource dataSource() throws SQLException {
        org.springframework.jdbc.datasource.SimpleDriverDataSource ds =
                new org.springframework.jdbc.datasource.SimpleDriverDataSource();
        ds.setDriver(new com.mysql.cj.jdbc.Driver());
        ds.setUrl(jdbcUrl());
        ds.setUsername(dbUser());
        ds.setPassword(dbPassword());
        return ds;
    }

    /**
     * 删除指定表：外部库模式下表会跨运行残留（testcontainers 每次新容器无此问题），先落为敬保证用例从干净状态起步。
     *
     * @param table 要删除的表名
     * @throws SQLException DROP 语句执行失败
     */
    private void dropTable(String table) throws SQLException {
        try (Connection conn = dataSource().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    /**
     * 预建表并把全局池计数行播种到指定值，构造"非零起步/回绕边界"等前置场景。
     *
     * @param table   目标表名
     * @param workerId 写入全局池行的 worker_id 值（下一取号即得该值 + 1）
     * @throws SQLException 建表或播种 INSERT 失败
     */
    private void seedPool(String table, int workerId) throws SQLException {
        try (Connection conn = dataSource().getConnection(); Statement stmt = conn.createStatement()) {
            // 表形态与 SequenceSql.MySql 方言一致：id 主键（__global__ 行固定 1）+ uk_service_name + gmt_*
            stmt.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "id BIGINT NOT NULL, service_name VARCHAR(64) NOT NULL,"
                    + "worker_id INT NOT NULL DEFAULT 0, instance VARCHAR(128) DEFAULT NULL,"
                    + "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (id), UNIQUE KEY uk_" + table + "_service_name (service_name)) ENGINE=InnoDB");
            stmt.execute("INSERT IGNORE INTO " + table + " (id, service_name, worker_id, instance) "
                    + "VALUES (1, '__global__', " + workerId + ", 'pool')");
        }
    }

    /** init-ddl=true 冷启动：全局池从 0 起步首取 1、次取 2 递增，且审计行记录最新占用。 */
    @Test
    void 连续分配_全局唯一且递增_审计行可见() throws Exception {
        String table = "sys_seq_t1";
        dropTable(table);
        MysqlWorkerIdAllocator allocator = new MysqlWorkerIdAllocator(
                dataSource(), table, "svc-a", "tag-a", true, 10,
                MysqlWorkerIdAllocator.WraparoundPolicy.RESET);

        long a = allocator.allocate();
        long b = allocator.allocate();
        assertThat(a).as("全局池从 0 起步，首取 = 1").isEqualTo(1);
        assertThat(b).isEqualTo(2);

        try (Connection conn = dataSource().getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT worker_id, instance FROM " + table + " WHERE service_name='svc-a'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(2);
            assertThat(rs.getString(2)).isEqualTo("tag-a");
        }
    }

    /** 三个服务各建分配器取号：共享全局池单点分配，跨服务不撞号（第三实例号必然大于 2）。 */
    @Test
    void 不同服务实例_共享全局池_不撞号() throws Exception {
        DataSource ds = dataSource();
        String table = "sys_seq_t2";
        dropTable(table);
        new MysqlWorkerIdAllocator(ds, table, "svc-a", "a1", true, 10,
                MysqlWorkerIdAllocator.WraparoundPolicy.RESET).allocate();
        new MysqlWorkerIdAllocator(ds, table, "svc-b", "b1", true, 10,
                MysqlWorkerIdAllocator.WraparoundPolicy.RESET).allocate();
        long c = new MysqlWorkerIdAllocator(ds, table, "svc-c", "c1", true, 10,
                MysqlWorkerIdAllocator.WraparoundPolicy.RESET).allocate();
        assertThat(c).as("全平台单点分配：第三实例的号必须大于前两个").isGreaterThan(2);
    }

    /**
     * BUG-009 回归：表<b>存在</b>但结构陈旧（旧版 DDL 无 id/gmt_* 列）时取号期抛错
     * fail-fast——生产实证旧结构会让取号 SQL 报 Unknown column 并静默回落 hash workerId
     * （跨服务撞号风险）。断言错误消息含缺失列与迁移指引。
     */
    @Test
    void 结构漂移_表存在缺契约列_取号期抛错() throws Exception {
        String table = "sys_seq_drift";
        dropTable(table);
        try (Connection conn = dataSource().getConnection();
             Statement stmt = conn.createStatement()) {
            // 旧版结构：无 id 主键、无 gmt_* 标准时间列
            stmt.execute("CREATE TABLE " + table + " ("
                    + "service_name VARCHAR(64) NOT NULL PRIMARY KEY, "
                    + "worker_id INT NOT NULL DEFAULT 0, "
                    + "instance VARCHAR(128))");
        }
        MysqlWorkerIdAllocator drifted = new MysqlWorkerIdAllocator(
                dataSource(), table, "svc-a", "tag-a", true, 10,
                MysqlWorkerIdAllocator.WraparoundPolicy.RESET);
        org.assertj.core.api.Assertions.assertThatThrownBy(drifted::allocate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GMT_MODIFIED")
                .hasMessageContaining("sys_sequence_ddl.sql");
    }

    /** 池预置 1023（10 位上限）：取到 1024 超限触发 RESET，重置为 0 后重新取号得 1。 */
    @Test
    void 回绕_RESET策略_重置后重新取号() throws Exception {
        String table = "sys_seq_t3";
        dropTable(table);
        seedPool(table, 1023);
        long workerId = new MysqlWorkerIdAllocator(dataSource(), table, "svc-wrap", "w1", true, 10,
                MysqlWorkerIdAllocator.WraparoundPolicy.RESET).allocate();
        // 1023+1=1024 超上限 1023 → 重置为 0 → 重新取号 = 1
        assertThat(workerId).isEqualTo(1);
    }

    /** 池预置 1023：FAIL 策略下取号超上限抛 SQLException（信息含 FAIL）拒绝启动。 */
    @Test
    void 回绕_FAIL策略_拒绝启动() throws Exception {
        String table = "sys_seq_t4";
        dropTable(table);
        seedPool(table, 1023);
        MysqlWorkerIdAllocator allocator = new MysqlWorkerIdAllocator(
                dataSource(), table, "svc-fail", "f1", true, 10,
                MysqlWorkerIdAllocator.WraparoundPolicy.FAIL);
        assertThatThrownBy(allocator::allocate)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("FAIL");
    }

    /** 自定义表名 + 6 位机器位（上限 63）：池推到边界验证 RESET 回绕与非默认位宽组合。 */
    @Test
    void 自定义表名与机器位宽度_端到端() throws Exception {
        // 6 位机器位 = 上限 63；推到 63 验证 RESET 边界与 6 位宽的组合
        String table = "sys_seq_t5";
        dropTable(table);
        seedPool(table, 63);
        long workerId = new MysqlWorkerIdAllocator(dataSource(), table, "svc-bits", "b1", true, 6,
                MysqlWorkerIdAllocator.WraparoundPolicy.RESET).allocate();
        assertThat(workerId).isEqualTo(1);
    }
}
