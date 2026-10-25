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
package com.xp.starsite.asynccmd.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DB 分布式锁（ShedLock 模式）：LEADER 调度模式下确保多实例只有 leader 执行 tick。
 * <p>
 * 原理：lock 表一行记录（lock_name 为主键），持锁者写入 {@code locked_by + locked_until}；
 * 争锁是单条原子 UPDATE——{@code WHERE locked_until < NOW()}，谁的 UPDATE 先命中谁获得锁，
 * 未命中直接返回 0（不阻塞）。锁有 TTL（超时自动释放——leader 崩溃后其他实例可在 TTL 后接管）。
 * <p>
 * 与 Redis/ZK 锁的取舍：组件已有 DB 依赖（零新增基础设施），锁粒度只需"每轮 tick 互斥"
 * 不需要强一致；DB 锁的秒级粒度足够（tick 周期本身 5s 起步）。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class DbScheduleLock {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(DbScheduleLock.class);

    /** 表名白名单（防注入） */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    /** 锁表 DDL（幂等 IF NOT EXISTS）——owner + TTL 模式 */
    private static final String LOCK_DDL = """
            CREATE TABLE IF NOT EXISTS {table} (
              lock_name   VARCHAR(64)  NOT NULL COMMENT '锁名（组件内固定使用，如 async-cmd-scheduler）',
              locked_by   VARCHAR(128) DEFAULT NULL COMMENT '当前持锁者标识',
              locked_until DATETIME    DEFAULT NULL COMMENT '持锁到期时刻（TTL 过后可被抢）',
              updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (lock_name)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组件分布式调度锁'""";

    /** 目标库连接池 */
    private final DataSource dataSource;

    /** 锁表名（白名单校验后） */
    private final String table;

    /** 锁名（同一组件的不同调度器用不同 lock_name） */
    private final String lockName;

    /** 持锁者标识（实例唯一） */
    private final String ownerId;

    /** 锁 TTL（毫秒）——超过此时间未续期视为崩溃，其他实例可抢锁 */
    private final long lockTtlMs;

    /**
     * 构造（含幂等建表）。
     *
     * @param dataSource 目标库
     * @param tableName  锁表名（仅字母数字下划线；默认 component_schedule_lock）
     * @param lockName   锁名（如 async-cmd-scheduler）
     * @param ownerId    持锁者标识（服务名@pid）
     * @param lockTtlMs  锁 TTL 毫秒数（leader 崩溃后其他实例在 TTL 后接管；应大于 tick 周期）
     */
    public DbScheduleLock(DataSource dataSource, String tableName, String lockName,
                          String ownerId, long lockTtlMs) {
        if (tableName == null || !SAFE_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("lock tableName 仅允许字母数字下划线: " + tableName);
        }
        this.dataSource = dataSource;
        this.table = tableName;
        this.lockName = lockName;
        this.ownerId = ownerId;
        this.lockTtlMs = lockTtlMs;
        ensureTable();
    }

    /**
     * 便捷构造（默认表名 component_schedule_lock）。
     *
     * @param dataSource 目标库
     * @param lockName   锁名
     * @param ownerId    持锁者标识
     * @param lockTtlMs  锁 TTL 毫秒
     */
    public DbScheduleLock(DataSource dataSource, String lockName, String ownerId, long lockTtlMs) {
        this(dataSource, "component_schedule_lock", lockName, ownerId, lockTtlMs);
    }

    /**
     * 幂等建锁表。
     */
    private void ensureTable() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(LOCK_DDL.replace("{table}", table))) {
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("[starsite-async-command] 分布式锁建表失败（可能 DBA 已建或无 DDL 权限）: {}", e.getMessage());
        }
    }

    /**
     * 争锁（原子、不阻塞）：locked_until 已过期（或从未持锁）则当前实例获得锁。
     * 获取成功即续期 locked_until = NOW() + TTL——leader 每轮 tick 前调用本方法即天然续期。
     *
     * @return true 获得锁（本轮 tick 可执行）；false 他方持锁中（跳过本轮）
     */
    public boolean tryLock() {
        // 两条路径合一：先"抢"（过期后占位），再"续"（自己持有时续期）。
        // MySQL 的 UPDATE 原子性保证多实例并发下只有一个命中。
        String sql = "UPDATE " + table + " SET locked_by = ?, locked_until = ? "
                + "WHERE lock_name = ? AND (locked_until IS NULL OR locked_until < NOW() OR locked_by = ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            LocalDateTime until = LocalDateTime.now().plusNanos(lockTtlMs * 1_000_000);
            ps.setString(1, ownerId);
            ps.setObject(2, until);
            ps.setString(3, lockName);
            ps.setString(4, ownerId);
            boolean acquired = ps.executeUpdate() > 0;
            if (acquired) {
                log.trace("[starsite-async-command] 获得调度锁: lock={}, owner={}", lockName, ownerId);
            }
            return acquired;
        } catch (SQLException e) {
            // 锁不可用不阻断 tick（fail-open——宁可多实例竞争也别停摆，令牌认领兜底正确性）
            log.warn("[starsite-async-command] 调度锁争用异常（fail-open 继续 tick）: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 主动释放锁（优雅停机时调用——leader 主动让位，不等 TTL）。
     */
    public void unlock() {
        String sql = "UPDATE " + table + " SET locked_by = NULL, locked_until = NULL "
                + "WHERE lock_name = ? AND locked_by = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, lockName);
            ps.setString(2, ownerId);
            ps.executeUpdate();
            log.info("[starsite-async-command] 调度锁已释放: lock={}, owner={}", lockName, ownerId);
        } catch (SQLException e) {
            log.warn("[starsite-async-command] 调度锁释放异常（等 TTL 自动过期）: {}", e.getMessage());
        }
    }
}
