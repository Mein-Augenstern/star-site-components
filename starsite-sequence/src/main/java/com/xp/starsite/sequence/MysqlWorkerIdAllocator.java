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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 分布式形态机器位分配器（MySQL sequence 表原子租约）。
 * <p>
 * 语义（对齐《分布式 ID：从雪花算法到生产实现》的 __global__ 池设计）：
 * <ul>
 *   <li><b>分配必须单点</b>：workerId 从全局池计数行（service_name = '__global__'）经
 *       {@code UPDATE ... SET worker_id = LAST_INSERT_ID(worker_id + 1)} 原子自增取出，
 *       全平台绝不重复——各服务独立从 0 数会立刻跨服务撞号，故不做每服务独立计数。</li>
 *   <li><b>审计分服务</b>：每个服务实例的占用记录（谁在何时占用过哪个机器位）另落各自的
 *       sequence 行，不影响分配语义。</li>
 *   <li><b>回绕策略</b>：分配值超过机器位上限时——{@link WraparoundPolicy#RESET} 重置全局池
 *       为 0 后重新取号并告警（回绕窗口内与存活旧实例同 id 需要「同毫秒同序列」同时命中，
 *       概率可忽略但必须可见）；{@link WraparoundPolicy#FAIL} 直接拒绝（保守场景）。</li>
 *   <li><b>建表随组件走</b>：表名可配（默认 sys_sequence），init-ddl=true 时自动
 *       CREATE TABLE IF NOT EXISTS（增量安全），也可交由 DBA 迁移脚本管理。</li>
 * </ul>
 * 全部 SQL 参数绑定；表名校验防注入（仅允许字母数字下划线）。
 *
 * @author muyi
 * @since 2026-10
 */
public class MysqlWorkerIdAllocator implements WorkerIdAllocator {

    /** 全局池计数行 key（workerId 从这一行原子自增取出，全平台唯一） */
    public static final String GLOBAL_POOL_KEY = "__global__";

    private static final Logger log = LoggerFactory.getLogger(MysqlWorkerIdAllocator.class);

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    /** 回绕策略 */
    public enum WraparoundPolicy {
        /** 重置全局池为 0 重新取号（默认；告警可见） */
        RESET,
        /** 超上限直接抛异常拒绝启动（保守） */
        FAIL
    }

    private final DataSource dataSource;
    private final String tableName;
    private final String serviceName;
    private final String instanceTag;
    private final boolean initDdl;
    private final long maxWorkerId;
    private final WraparoundPolicy wraparoundPolicy;

    public MysqlWorkerIdAllocator(DataSource dataSource, String tableName, String serviceName,
                                  String instanceTag, boolean initDdl, int workerIdBits,
                                  WraparoundPolicy wraparoundPolicy) {
        if (dataSource == null) {
            throw new IllegalArgumentException("mysql 模式需要 DataSource（检查数据源配置）");
        }
        if (tableName == null || !SAFE_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("tableName 仅允许字母数字下划线: " + tableName);
        }
        this.dataSource = dataSource;
        this.tableName = tableName;
        this.serviceName = serviceName;
        this.instanceTag = instanceTag;
        this.initDdl = initDdl;
        this.maxWorkerId = ~(-1L << workerIdBits);
        this.wraparoundPolicy = wraparoundPolicy;
    }

    @Override
    public long allocate() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            if (initDdl) {
                ensureTable(conn);
            }
            long workerId = takeFromGlobalPool(conn);
            recordOwnership(conn, workerId);
            if (workerId > maxWorkerId) {
                if (wraparoundPolicy == WraparoundPolicy.FAIL) {
                    throw new SQLException("全局池已分配到 %d 超过机器位上限 %d，按 FAIL 策略拒绝"
                            .formatted(workerId, maxWorkerId));
                }
                // RESET：重置全局池后重新取号（两次取号间仍有理论撞号窗口，靠告警可见）
                log.warn("[starsite-sequence] workerId 回绕：全局池已分配到 {} 超过机器位上限 {}，"
                        + "重置全局池重新取号（回绕窗口内与存活旧实例同 id 需同毫秒同序列同时命中，"
                        + "概率可忽略但必须可见；实例规模接近上限时应扩位宽）", workerId, maxWorkerId);
                resetGlobalPool(conn);
                workerId = takeFromGlobalPool(conn);
                recordOwnership(conn, workerId);
            }
            return workerId;
        }
    }

    @Override
    public String describe() {
        return "mysql-lease(table=%s, service=%s, tag=%s)".formatted(tableName, serviceName, instanceTag);
    }

    private void ensureTable(Connection conn) throws SQLException {
        String ddl = """
                CREATE TABLE IF NOT EXISTS %s (
                  service_name VARCHAR(64) NOT NULL COMMENT '服务名（__global__=全局池计数行，其余为实例占用记录）',
                  worker_id    INT NOT NULL DEFAULT 0 COMMENT '该行机器位（全局池行=已分配到的最大值）',
                  instance     VARCHAR(128) DEFAULT NULL COMMENT '最近占用实例标识',
                  updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  PRIMARY KEY (service_name)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='雪花 worker-id 分配序列表'""".formatted(tableName);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
            try (PreparedStatement seed = conn.prepareStatement(
                    "INSERT IGNORE INTO " + tableName + " (service_name, worker_id, instance) VALUES (?, 0, ?)")) {
                seed.setString(1, GLOBAL_POOL_KEY);
                seed.setString(2, "pool");
                seed.executeUpdate();
            }
        }
    }

    /** 原子自增取出全局唯一机器位：LAST_INSERT_ID(expr) 在 UPDATE 命中时把自增结果带回会话 */
    private long takeFromGlobalPool(Connection conn) throws SQLException {
        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE " + tableName + " SET worker_id = LAST_INSERT_ID(worker_id + 1), "
                        + "instance = ?, updated_at = NOW() WHERE service_name = ?");
             PreparedStatement select = conn.prepareStatement("SELECT LAST_INSERT_ID()")) {
            update.setString(1, instanceTag);
            update.setString(2, GLOBAL_POOL_KEY);
            update.executeUpdate();
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("LAST_INSERT_ID() 无返回");
                }
                return rs.getLong(1);
            }
        }
    }

    /** 记录服务实例的占用（各自 sequence 行，审计可见；不影响分配语义） */
    private void recordOwnership(Connection conn, long workerId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO " + tableName + " (service_name, worker_id, instance, updated_at) "
                        + "VALUES (?, ?, ?, NOW()) "
                        + "ON DUPLICATE KEY UPDATE worker_id = VALUES(worker_id), "
                        + "instance = VALUES(instance), updated_at = NOW()")) {
            stmt.setString(1, serviceName);
            stmt.setLong(2, workerId);
            stmt.setString(3, instanceTag);
            stmt.executeUpdate();
        }
    }

    private void resetGlobalPool(Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "UPDATE " + tableName + " SET worker_id = 0, instance = ? WHERE service_name = ?")) {
            stmt.setString(1, instanceTag + "(wraparound-reset)");
            stmt.setString(2, GLOBAL_POOL_KEY);
            stmt.executeUpdate();
        }
    }
}
