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
 *
 * @author muyi
 * @since 2026-10
 */
public class JdbcAsyncCommandStore implements AsyncCommandStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcAsyncCommandStore.class);

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    private final JdbcTemplate jdbc;
    private final String table;

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

    @Override
    public AsyncCommand findById(long id) {
        List<AsyncCommand> out = jdbc.query(
                "SELECT * FROM " + table + " WHERE id = ?", MAPPER, id);
        return out.isEmpty() ? null : out.get(0);
    }

    @Override
    public long countUnfinished(String cmdType, String bizKey) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE cmd_type = ? AND biz_key = ? "
                        + "AND status IN ('PENDING', 'RUNNING')",
                Long.class, cmdType, bizKey);
        return n == null ? 0 : n;
    }

    @Override
    public int claim(String token, int limit, LocalDateTime now) {
        // 到期判定用参数时间（与调用方/测试时钟一致），updated_at 仍取库时间
        return jdbc.update("UPDATE " + table + " SET status = 'RUNNING', claim_token = ?, updated_at = NOW() "
                        + "WHERE status = 'PENDING' AND next_run_at <= ? "
                        + "AND id IN (SELECT id FROM (SELECT id FROM " + table
                        + " WHERE status = 'PENDING' AND next_run_at <= ? ORDER BY id LIMIT ?) t)",
                token, now, now, limit);
    }

    @Override
    public List<AsyncCommand> findByToken(String token) {
        return jdbc.query("SELECT * FROM " + table + " WHERE claim_token = ? ORDER BY id", MAPPER, token);
    }

    @Override
    public int reclaimStale(int staleAfterSeconds, int limit) {
        // 悬挂判定与回写都用数据库时钟：updated_at 由 DB 维护（ON UPDATE CURRENT_TIMESTAMP），
        // 用应用时钟对比会因两机时差误判（claim 的到期判定用参数时间，两者语义不同——
        // claim 判的是业务到期，这里判的是"DB 眼里多久没动静"）
        return jdbc.update("UPDATE " + table + " SET status = 'PENDING', claim_token = NULL, updated_at = NOW() "
                        + "WHERE status = 'RUNNING' AND updated_at < NOW() - INTERVAL ? SECOND LIMIT ?",
                staleAfterSeconds, limit);
    }

    @Override
    public int updateState(AsyncCommand cmd) {
        return jdbc.update("UPDATE " + table + " SET status = ?, claim_token = ?, retry_count = ?, "
                        + "next_run_at = ?, last_error = ?, updated_at = NOW() WHERE id = ?",
                cmd.getStatus(), cmd.getClaimToken(), cmd.getRetryCount(),
                cmd.getNextRunAt(), cmd.getLastError(), cmd.getId());
    }

    @Override
    public long countByStatus(String status) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE status = ?", Long.class, status);
        return n == null ? 0 : n;
    }

    @Override
    public long countBacklogDue(LocalDateTime now) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE status = 'PENDING' AND next_run_at <= ?",
                Long.class, now);
        return n == null ? 0 : n;
    }

    @Override
    public List<AsyncCommand> selectRecent(int limit) {
        return jdbc.query("SELECT * FROM " + table + " ORDER BY id DESC LIMIT ?", MAPPER, limit);
    }

    @Override
    public long countPage(String status, String cmdType) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(table).append(" WHERE 1 = 1");
        Object[] args = pageArgs(sql, status, cmdType);
        Long n = jdbc.queryForObject(sql.toString(), Long.class, args);
        return n == null ? 0 : n;
    }

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
