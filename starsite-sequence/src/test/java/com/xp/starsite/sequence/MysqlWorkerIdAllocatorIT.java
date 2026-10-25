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
package com.xp.starsite.sequence;

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
 * @since 2026-10
 */
class MysqlWorkerIdAllocatorIT {

    private static final String EXT_URL = System.getenv("TEST_MYSQL_URL");

    private static MySQLContainer<?> container;

    @BeforeAll
    static void startDb() {
        if (EXT_URL == null) {
            container = new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("starsite").withUsername("test").withPassword("test");
            container.start();
        }
    }

    @AfterAll
    static void stopDb() {
        if (container != null) {
            container.stop();
        }
    }

    private static String jdbcUrl() {
        return EXT_URL != null ? EXT_URL : container.getJdbcUrl();
    }

    private static String dbUser() {
        return EXT_URL != null ? env("TEST_MYSQL_USER", "root") : container.getUsername();
    }

    private static String dbPassword() {
        return EXT_URL != null ? env("TEST_MYSQL_PASSWORD", "root") : container.getPassword();
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null ? def : v;
    }

    private DataSource dataSource() throws SQLException {
        org.springframework.jdbc.datasource.SimpleDriverDataSource ds =
                new org.springframework.jdbc.datasource.SimpleDriverDataSource();
        ds.setDriver(new com.mysql.cj.jdbc.Driver());
        ds.setUrl(jdbcUrl());
        ds.setUsername(dbUser());
        ds.setPassword(dbPassword());
        return ds;
    }

    /** 外部库模式下表会跨运行残留（testcontainers 每次新容器无此问题），先落为敬 */
    private void dropTable(String table) throws SQLException {
        try (Connection conn = dataSource().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    private void seedPool(String table, int workerId) throws SQLException {
        try (Connection conn = dataSource().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "service_name VARCHAR(64) NOT NULL, worker_id INT NOT NULL DEFAULT 0,"
                    + "instance VARCHAR(128) DEFAULT NULL,"
                    + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (service_name)) ENGINE=InnoDB");
            stmt.execute("INSERT IGNORE INTO " + table + " (service_name, worker_id, instance) "
                    + "VALUES ('__global__', " + workerId + ", 'pool')");
        }
    }

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
