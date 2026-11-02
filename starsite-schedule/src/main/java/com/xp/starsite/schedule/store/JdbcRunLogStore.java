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
package com.xp.starsite.schedule.store;

import com.xp.starsite.schedule.api.RunLog;
import com.xp.starsite.schedule.api.RunStatus;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JDBC 流水存储（MySQL 方言默认；语句全部来自 {@link ScheduleRunLogSql}——Java 零
 * SQL 字符串）。id 由 sequence 组件预生成（LongSupplier 注入，与 async-command 同形）。
 *
 * @author muyi
 * @date 2026/11/02
 */
public class JdbcRunLogStore implements RunLogStore {

    /** JdbcTemplate（消费方数据源包装） */
    private final JdbcTemplate jdbc;

    /** 表名（白名单校验） */
    private final String table;

    /** SQL 方言 */
    private final ScheduleRunLogSql sql;

    /** id 生成器（sequence 组件） */
    private final LongSupplier idGenerator;

    /** 行映射（列表列——不含 error；详情用 FULL_MAPPER） */
    private static final RowMapper<RunLog> LIST_MAPPER = (rs, i) -> new RunLog(
            rs.getLong("id"), rs.getString("task_name"), rs.getString("lock_name"),
            toTime(rs.getTimestamp("trigger_time")), toTime(rs.getTimestamp("started_at")),
            toTime(rs.getTimestamp("finished_at")), rs.getString("host"),
            RunStatus.valueOf(rs.getString("status")),
            rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"), null);

    /** 行映射（全列含 error） */
    private static final RowMapper<RunLog> FULL_MAPPER = (rs, i) -> new RunLog(
            rs.getLong("id"), rs.getString("task_name"), rs.getString("lock_name"),
            toTime(rs.getTimestamp("trigger_time")), toTime(rs.getTimestamp("started_at")),
            toTime(rs.getTimestamp("finished_at")), rs.getString("host"),
            RunStatus.valueOf(rs.getString("status")),
            rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"),
            rs.getString("error"));

    /**
     * 便捷构造（MySQL 默认方言 + 构造期建表）。
     *
     * @param jdbc        JdbcTemplate
     * @param table       表名（[A-Za-z0-9_] 白名单）
     * @param idGenerator id 生成器（sequence 组件）
     */
    public JdbcRunLogStore(JdbcTemplate jdbc, String table, LongSupplier idGenerator) {
        this(jdbc, table, new ScheduleRunLogSql.MySql(), true, idGenerator);
    }

    /**
     * 全参构造。
     *
     * @param jdbc        JdbcTemplate
     * @param table       表名（[A-Za-z0-9_] 白名单，非法即抛——防 {table} 渲染注入）
     * @param sql         SQL 方言（自定义方言实现其他数据库适配）
     * @param initDdl     true 构造期自动建表（IF NOT EXISTS 幂等）；false 交由迁移脚本管理
     * @param idGenerator id 生成器（sequence 组件）
     */
    public JdbcRunLogStore(JdbcTemplate jdbc, String table, ScheduleRunLogSql sql,
                           boolean initDdl, LongSupplier idGenerator) {
        if (table == null || !table.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("表名非法（白名单 [A-Za-z0-9_]）: " + table);
        }
        this.jdbc = jdbc;
        this.table = table;
        this.sql = sql;
        this.idGenerator = idGenerator;
        if (initDdl) {
            jdbc.execute(render(sql.createTable()));
        }
    }

    /** {@inheritDoc} */
    @Override
    public RunLog start(String taskName, String lockName, LocalDateTime triggerTime,
                        LocalDateTime startedAt, String host) {
        return insertRow(taskName, lockName, triggerTime, startedAt, host, RunStatus.RUNNING, null);
    }

    /** {@inheritDoc} */
    @Override
    public RunLog skip(String taskName, String lockName, LocalDateTime triggerTime,
                       LocalDateTime at, String host) {
        return insertRow(taskName, lockName, triggerTime, at, host, RunStatus.SKIPPED_LOCKED, 0L);
    }

    /** {@inheritDoc} */
    @Override
    public void finish(long id, RunStatus status, Long durationMs, String error) {
        jdbc.update(render(sql.finish()), ps -> {
            ps.setString(1, status.name());
            ps.setObject(2, durationMs);
            ps.setString(3, error);
            ps.setLong(4, id);
        });
    }

    /** {@inheritDoc} */
    @Override
    public int markStaleRunning(LocalDateTime olderThan) {
        return jdbc.update(render(sql.markStaleRunning()), ps -> ps.setTimestamp(1,
                Timestamp.valueOf(olderThan)));
    }

    /** {@inheritDoc} */
    @Override
    public List<RunLog> recentByTask(String taskName, int limit) {
        return jdbc.query(render(sql.recentByTask()),
                ps -> {
                    ps.setString(1, taskName);
                    ps.setInt(2, Math.max(0, limit));
                }, LIST_MAPPER);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<RunLog> findById(long id) {
        List<RunLog> rows = jdbc.query(render(sql.findById()), ps -> ps.setLong(1, id), FULL_MAPPER);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<LocalDateTime> lastDoneTriggerTime(String taskName) {
        List<LocalDateTime> rows = jdbc.query(render(sql.lastDoneTriggerTime()),
                ps -> ps.setString(1, taskName),
                (rs, i) -> toTime(rs.getTimestamp("trigger_time")));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 插入一行并回读构造（终态直插形态：start=RUNNING / skip=SKIPPED_LOCKED） */
    private RunLog insertRow(String taskName, String lockName, LocalDateTime triggerTime,
                             LocalDateTime at, String host, RunStatus status, Long durationMs) {
        long id = idGenerator.getAsLong();
        jdbc.update(render(sql.insert()), ps -> {
            ps.setLong(1, id);
            ps.setString(2, taskName);
            ps.setString(3, lockName);
            ps.setTimestamp(4, Timestamp.valueOf(triggerTime));
            ps.setTimestamp(5, Timestamp.valueOf(at));
            ps.setTimestamp(6, status == RunStatus.RUNNING ? null : Timestamp.valueOf(at));
            ps.setString(7, host);
            ps.setString(8, status.name());
            ps.setObject(9, durationMs);
        });
        return new RunLog(id, taskName, lockName, triggerTime, at,
                status == RunStatus.RUNNING ? null : at, host, status, durationMs, null);
    }

    /** {table} 占位渲染（表名已在构造期白名单校验） */
    private String render(String stmt) {
        return stmt.replace("{table}", table);
    }

    /** Timestamp → LocalDateTime（null 安全） */
    private static LocalDateTime toTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
