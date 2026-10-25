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

import com.xp.starsite.asynccmd.AsyncCommand;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * MySQL 表存储（单机/分布式通用形态）：两步令牌法认领，多实例并发互斥。
 * <p>
 * 与 star-site-backend star-async 的 async_command 表结构兼容（平滑迁移）；
 * 表名可配（仅允许字母数字下划线，防注入）。init-ddl=true 时自动建表（增量安全）。
 * 方言：MySQL（UPDATE 子查询认领 / INTERVAL 回收），其他库需自行适配 SQL。
 * 存储技术选型为 spring-jdbc（JdbcTemplate）——core 不引 Spring Boot，仅依赖 spring-jdbc。
 * <p>
 * 线程安全性：JdbcTemplate 无状态、每次调用独立取连接，方法可多线程并发调用。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class JdbcAsyncCommandStore implements AsyncCommandStore {

    /** 日志（slf4j） */
    private static final Logger log = LoggerFactory.getLogger(JdbcAsyncCommandStore.class);

    /** 表名白名单（仅字母数字下划线）：表名须拼接进 SQL 无法参数化，白名单校验是防注入的唯一手段 */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    /** JDBC 操作模板（无状态，线程安全） */
    private final JdbcTemplate jdbc;
    /** 命令表名（构造期已通过白名单校验，可安全拼接） */
    private final String table;

    /** 行映射器：结果集 → AsyncCommand（无状态 lambda，静态复用） */
    private static final RowMapper<AsyncCommand> MAPPER = (rs, i) -> {
        AsyncCommand c = new AsyncCommand();
        c.setId(rs.getLong("id"));
        c.setCmdType(rs.getString("cmd_type"));
        c.setBizKey(rs.getString("biz_key"));
        c.setPayload(rs.getString("payload"));
        c.setStatus(rs.getString("status"));
        c.setMaxRetry(rs.getInt("max_retry"));
        c.setRetryCount(rs.getInt("retry_count"));
        c.setNextRunAt(rs.getTimestamp("next_run_at") == null ? null : rs.getTimestamp("next_run_at").toLocalDateTime());
        c.setLastError(rs.getString("last_error"));
        c.setOwner(rs.getString("owner"));
        c.setClaimToken(rs.getString("claim_token"));
        c.setCreatedAt(rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime());
        c.setUpdatedAt(rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime());
        return c;
    };

    /**
     * 构造表存储。
     *
     * @param jdbc      JDBC 模板（无状态，线程安全）
     * @param tableName 命令表名（仅允许字母数字下划线——表名要拼接进 SQL，白名单防注入）
     * @param initDdl   true 时自动建表（CREATE TABLE IF NOT EXISTS，增量安全，表已存在则跳过）
     * @throws IllegalArgumentException 表名为 null 或含白名单外字符
     */
    public JdbcAsyncCommandStore(JdbcTemplate jdbc, String tableName, boolean initDdl) {
        if (tableName == null || !SAFE_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("tableName 仅允许字母数字下划线: " + tableName);
        }
        this.jdbc = jdbc;
        this.table = tableName;
        if (initDdl) {
            ensureTable();
        }
    }

    /**
     * 自动建表（幂等：IF NOT EXISTS，表已存在则跳过）。
     * 建表失败只记 warn 不抛出：表可能由 DBA/迁移脚本统一管理，真有问题会在首条命令提交时暴露。
     */
    private void ensureTable() {
        try {
            jdbc.execute(("""
                    CREATE TABLE IF NOT EXISTS %s (
                      id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                      cmd_type    VARCHAR(64)  NOT NULL COMMENT '命令类型=执行器路由键',
                      biz_key     VARCHAR(128) DEFAULT '' COMMENT '业务键(幂等/追踪)',
                      payload     TEXT         DEFAULT NULL COMMENT '命令载荷 JSON',
                      status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/DEAD',
                      max_retry   INT          NOT NULL DEFAULT 5,
                      retry_count INT          NOT NULL DEFAULT 0,
                      next_run_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '到期时间(延迟队列锚点)',
                      last_error  VARCHAR(500) DEFAULT NULL,
                      owner       VARCHAR(64)  DEFAULT '' COMMENT '提交方服务名',
                      claim_token VARCHAR(64)  DEFAULT NULL COMMENT '调度认领令牌',
                      created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      PRIMARY KEY (id),
                      KEY idx_status_next (status, next_run_at),
                      KEY idx_type_biz (cmd_type, biz_key)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='异步命令'""").formatted(table));
            log.info("[starsite-async-command] 表 {} 就绪（IF NOT EXISTS）", table);
        } catch (Exception e) {
            // 建表失败不阻断启动（表可能由 DBA/迁移脚本管理），首条命令提交时即会暴露
            log.warn("[starsite-async-command] 建表检查失败（不阻断启动）: {}", e.getMessage());
        }
    }

    /**
     * 新建命令：INSERT 后以 GeneratedKeyHolder 回填自增 id。
     *
     * @param cmd 待落库命令（id 由数据库自增分配并回填）
     */
    @Override
    public void insert(AsyncCommand cmd) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + table + " (cmd_type, biz_key, payload, status, max_retry, retry_count, "
                            + "next_run_at, owner) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, cmd.getCmdType());
            ps.setString(2, cmd.getBizKey() == null ? "" : cmd.getBizKey());
            ps.setString(3, cmd.getPayload());
            ps.setString(4, cmd.getStatus());
            ps.setInt(5, cmd.getMaxRetry());
            ps.setInt(6, cmd.getRetryCount());
            ps.setObject(7, cmd.getNextRunAt());
            ps.setString(8, cmd.getOwner());
            return ps;
        }, kh);
        if (!kh.getKeyList().isEmpty() && kh.getKeyList().get(0) != null) {
            cmd.setId(((Number) kh.getKeyList().get(0).values().iterator().next()).longValue());
        }
    }

    /**
     * 按主键查询命令。
     *
     * @param id 命令主键
     * @return 命令；不存在返回 null
     */
    @Override
    public AsyncCommand findById(long id) {
        List<AsyncCommand> out = jdbc.query(
                "SELECT * FROM " + table + " WHERE id = ?", MAPPER, id);
        return out.isEmpty() ? null : out.get(0);
    }

    /**
     * 幂等检查：统计同 type+bizKey 下状态为 PENDING/RUNNING 的命令数（命中 idx_type_biz 索引）。
     *
     * @param cmdType 命令类型（精确匹配）
     * @param bizKey  业务键（精确匹配）
     * @return 未完成命令条数
     */
    @Override
    public long countUnfinished(String cmdType, String bizKey) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE cmd_type = ? AND biz_key = ? "
                        + "AND status IN ('PENDING', 'RUNNING')",
                Long.class, cmdType, bizKey);
        return n == null ? 0 : n;
    }

    /**
     * 乐观认领（两步令牌法第一步）：单条 UPDATE 原子完成"选出到期 PENDING + 打令牌置 RUNNING"，
     * 并发实例互斥靠该语句的行锁保证。子查询套一层派生表：MySQL 不允许 UPDATE 直接子查询目标表。
     *
     * @param token 本轮认领令牌
     * @param limit 单轮认领上限（条）
     * @param now   到期判定基准时间
     * @return 实际认领条数（即 UPDATE 影响行数）
     */
    @Override
    public int claim(String token, int limit, LocalDateTime now) {
        // 到期判定用参数时间（与调用方/测试时钟一致），updated_at 仍取库时间
        return jdbc.update("UPDATE " + table + " SET status = 'RUNNING', claim_token = ?, updated_at = NOW() "
                        + "WHERE status = 'PENDING' AND next_run_at <= ? "
                        + "AND id IN (SELECT id FROM (SELECT id FROM " + table
                        + " WHERE status = 'PENDING' AND next_run_at <= ? ORDER BY id LIMIT ?) t)",
                token, now, now, limit);
    }

    /**
     * 按令牌取回本轮认领的命令。
     *
     * @param token 认领时使用的令牌
     * @return 命令列表（id 升序）；无命中返回空列表
     */
    @Override
    public List<AsyncCommand> findByToken(String token) {
        return jdbc.query("SELECT * FROM " + table + " WHERE claim_token = ? ORDER BY id", MAPPER, token);
    }

    /**
     * 崩溃回收：RUNNING 且 updated_at 早于库时钟 NOW() - staleAfterSeconds 的行回 PENDING 清令牌。
     * 悬挂判定刻意用数据库时钟——updated_at 由 DB 维护，多机应用时钟有偏差时用应用时钟会误判
     * （与 claim 的参数时间语义不同：claim 判业务到期，这里判"DB 眼里多久没动静"）。
     *
     * @param staleAfterSeconds 悬挂判定阈值（秒）
     * @param limit             单轮回收上限（条）
     * @return 实际回收条数
     */
    @Override
    public int reclaimStale(int staleAfterSeconds, int limit) {
        // 悬挂判定与回写都用数据库时钟：updated_at 由 DB 维护（ON UPDATE CURRENT_TIMESTAMP），
        // 用应用时钟对比会因两机时差误判（claim 的到期判定用参数时间，两者语义不同——
        // claim 判的是业务到期，这里判的是"DB 眼里多久没动静"）
        return jdbc.update("UPDATE " + table + " SET status = 'PENDING', claim_token = NULL, updated_at = NOW() "
                        + "WHERE status = 'RUNNING' AND updated_at < NOW() - INTERVAL ? SECOND LIMIT ?",
                staleAfterSeconds, limit);
    }

    /**
     * 状态回写：按 id 更新 status/claimToken/retryCount/nextRunAt/lastError 五个字段。
     *
     * @param cmd 携带最新状态字段的命令
     * @return 影响行数：1 成功；0 命令行不存在
     */
    @Override
    public int updateState(AsyncCommand cmd) {
        return jdbc.update("UPDATE " + table + " SET status = ?, claim_token = ?, retry_count = ?, "
                        + "next_run_at = ?, last_error = ?, updated_at = NOW() WHERE id = ?",
                cmd.getStatus(), cmd.getClaimToken(), cmd.getRetryCount(),
                cmd.getNextRunAt(), cmd.getLastError(), cmd.getId());
    }

    /**
     * 按状态计数。
     *
     * @param status 状态（四常量之一）
     * @return 该状态命令条数
     */
    @Override
    public long countByStatus(String status) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE status = ?", Long.class, status);
        return n == null ? 0 : n;
    }

    /**
     * 积压统计：已到期（next_run_at ≤ now）的 PENDING 条数。
     *
     * @param now 到期判定基准时间
     * @return 到期待执行条数
     */
    @Override
    public long countBacklogDue(LocalDateTime now) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE status = 'PENDING' AND next_run_at <= ?",
                Long.class, now);
        return n == null ? 0 : n;
    }

    /**
     * 最近命令查询。
     *
     * @param limit 返回条数上限（条）
     * @return 命令列表（id 倒序，最新在前）
     */
    @Override
    public List<AsyncCommand> selectRecent(int limit) {
        return jdbc.query("SELECT * FROM " + table + " ORDER BY id DESC LIMIT ?", MAPPER, limit);
    }

    /**
     * 分页计数（与 selectPage 同口径过滤）。
     *
     * @param status  状态过滤（null 或空白 = 不过滤）
     * @param cmdType 命令类型过滤（null 或空白 = 不过滤）
     * @return 满足过滤条件的总条数
     */
    @Override
    public long countPage(String status, String cmdType) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(table).append(" WHERE 1 = 1");
        Object[] args = pageArgs(sql, status, cmdType);
        Long n = jdbc.queryForObject(sql.toString(), Long.class, args);
        return n == null ? 0 : n;
    }

    /**
     * 分页查询（与 countPage 同口径过滤）。
     *
     * @param status  状态过滤（null 或空白 = 不过滤）
     * @param cmdType 命令类型过滤（null 或空白 = 不过滤）
     * @param offset  行偏移（0 起，非页码）
     * @param size    页大小（条）
     * @return 当前页命令（id 倒序）
     */
    @Override
    public List<AsyncCommand> selectPage(String status, String cmdType, long offset, int size) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(table).append(" WHERE 1 = 1");
        Object[] filtered = pageArgs(sql, status, cmdType);
        Object[] args = new Object[filtered.length + 2];
        System.arraycopy(filtered, 0, args, 0, filtered.length);
        args[filtered.length] = size;
        args[filtered.length + 1] = offset;
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        return jdbc.query(sql.toString(), MAPPER, args);
    }

    /**
     * 拼接可选过滤条件并收集绑定参数（status/cmdType 为 null 或空白时跳过对应条件）。
     * 过滤值一律走 ? 占位符参数绑定，绝不拼进 SQL 文本。
     *
     * @param sql     待追加条件的 SQL 前缀（"WHERE 1 = 1" 已就位，本方法只追加 AND 子句）
     * @param status  状态过滤（可 null）
     * @param cmdType 命令类型过滤（可 null）
     * @return 与追加的占位符一一对应的参数数组（可能为空数组）
     */
    private Object[] pageArgs(StringBuilder sql, String status, String cmdType) {
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (cmdType != null && !cmdType.isBlank()) {
            sql.append(" AND cmd_type = ?");
            args.add(cmdType);
        }
        return args.toArray();
    }
}
