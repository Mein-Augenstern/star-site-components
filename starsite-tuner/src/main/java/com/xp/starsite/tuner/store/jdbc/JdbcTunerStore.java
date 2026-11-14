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
package com.xp.starsite.tuner.store.jdbc;

import com.xp.starsite.tuner.api.TunerAuditEntry;
import com.xp.starsite.tuner.api.TunerPreset;
import com.xp.starsite.tuner.api.TunerWindow;
import com.xp.starsite.tuner.store.TunerSql;
import com.xp.starsite.tuner.store.TunerStore;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JDBC 治理存储（MySQL 方言默认；语句全部来自 {@link TunerSql}——Java 零 SQL 字符串）。
 * 分布式的共享参数源：所有实例轮询本表，窗口求值后各自应用收敛。
 *
 * @author muyi
 * @date 2026/11/14
 */
public class JdbcTunerStore implements TunerStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcTunerStore.class);

    /** 表名白名单（前缀+固定后缀拼接进 SQL，白名单是防注入唯一手段） */
    private static final java.util.regex.Pattern SAFE_IDENTIFIER =
            java.util.regex.Pattern.compile("[A-Za-z0-9_]+");

    /** JdbcTemplate */
    private final JdbcTemplate jdbc;

    /** 四表名（前缀渲染） */
    private final String presetTable;
    private final String windowTable;
    private final String bindingTable;
    private final String auditTable;

    /** SQL 方言 */
    private final TunerSql sql;

    /** id 发号（sequence 组件） */
    private final LongSupplier idGenerator;

    /** 时间源（审计时刻） */
    private final com.xp.starsite.tuner.clock.TimeSource time;

    /** 预案行映射 */
    private static final RowMapper<TunerPreset> PRESET_MAPPER = (rs, i) -> new TunerPreset(
            rs.getString("preset_name"), rs.getLong("core_size"),
            rs.getLong("max_size"), rs.getLong("keep_alive_seconds"));

    /** 窗口行映射 */
    private static final RowMapper<TunerWindow> WINDOW_MAPPER = (rs, i) -> new TunerWindow(
            rs.getLong("id"), rs.getString("pool_name"), rs.getString("preset_name"),
            rs.getString("start_cron"), rs.getString("end_cron"));

    /** 审计行映射 */
    private static final RowMapper<TunerAuditEntry> AUDIT_MAPPER = (rs, i) -> new TunerAuditEntry(
            rs.getLong("id"), toTime(rs.getTimestamp("occurred_at")), rs.getString("host"),
            rs.getString("pool_name"), rs.getString("preset_name"),
            TunerAuditEntry.Source.valueOf(rs.getString("source")),
            rs.getInt("old_core"), rs.getInt("old_max"), rs.getInt("old_keep"),
            rs.getInt("new_core"), rs.getInt("new_max"), rs.getInt("new_keep"));

    /**
     * 全参构造（构造期建表 + 幂等）。
     *
     * @param jdbc          JdbcTemplate
     * @param tablePrefix   表前缀（白名单 [A-Za-z0-9_]；三表 = 前缀_preset/_window/_binding/_audit）
     * @param sql           SQL 方言
     * @param initDdl       true 构造期建表
     * @param idGenerator   id 发号器（sequence 组件）
     * @param time          时间源
     * @throws IllegalArgumentException 前缀含白名单外字符
     */
    public JdbcTunerStore(JdbcTemplate jdbc, String tablePrefix, TunerSql sql, boolean initDdl,
                          LongSupplier idGenerator, com.xp.starsite.tuner.clock.TimeSource time) {
        if (tablePrefix == null || !SAFE_IDENTIFIER.matcher(tablePrefix).matches()) {
            throw new IllegalArgumentException("表前缀仅允许字母数字下划线: " + tablePrefix);
        }
        this.jdbc = jdbc;
        this.presetTable = tablePrefix + "_preset";
        this.windowTable = tablePrefix + "_window";
        this.bindingTable = tablePrefix + "_binding";
        this.auditTable = tablePrefix + "_audit";
        this.sql = sql;
        this.idGenerator = idGenerator;
        this.time = time;
        if (initDdl) {
            for (String ddl : new String[]{sql.createPresetTable(), sql.createWindowTable(),
                    sql.createBindingTable(), sql.createAuditTable()}) {
                try {
                    jdbc.execute(render(ddl));
                } catch (Exception e) {
                    log.warn("[starsite-tuner] 建表检查失败（不阻断启动）: {}", e.getMessage());
                }
            }
            log.info("[starsite-tuner] 治理三表就绪（前缀 {}）", tablePrefix);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void upsertPreset(TunerPreset preset) {
        jdbc.update(render(sql.upsertPreset()), ps -> {
            ps.setLong(1, idGenerator.getAsLong());
            ps.setString(2, preset.getPresetName());
            ps.setInt(3, preset.getCoreSize());
            ps.setInt(4, preset.getMaxSize());
            ps.setInt(5, preset.getKeepAliveSeconds());
        });
    }

    /** {@inheritDoc} */
    @Override
    public Optional<TunerPreset> preset(String name) {
        List<TunerPreset> rows = jdbc.query(render(sql.findPreset()),
                ps -> ps.setString(1, name), PRESET_MAPPER);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** {@inheritDoc} */
    @Override
    public List<TunerPreset> presets() {
        return jdbc.query(render(sql.listPresets()), PRESET_MAPPER);
    }

    /** {@inheritDoc} */
    @Override
    public void bindDefault(String poolName, String presetName) {
        jdbc.update(render(sql.upsertBinding()), ps -> {
            ps.setLong(1, idGenerator.getAsLong());
            ps.setString(2, poolName);
            ps.setString(3, presetName);
        });
    }

    /** {@inheritDoc} */
    @Override
    public Optional<String> defaultPreset(String poolName) {
        List<String> rows = jdbc.query(render(sql.findBinding()),
                ps -> ps.setString(1, poolName), (rs, i) -> rs.getString("preset_name"));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** {@inheritDoc} */
    @Override
    public TunerWindow upsertWindow(TunerWindow window) {
        if (window.getId() > 0) {
            jdbc.update(render(sql.updateWindow()), ps -> {
                ps.setString(1, window.getPoolName());
                ps.setString(2, window.getPresetName());
                ps.setString(3, window.getStartCron());
                ps.setString(4, window.getEndCron());
                ps.setLong(5, window.getId());
            });
            return window;
        }
        long id = idGenerator.getAsLong();
        jdbc.update(render(sql.insertWindow()), ps -> {
            ps.setLong(1, id);
            ps.setString(2, window.getPoolName());
            ps.setString(3, window.getPresetName());
            ps.setString(4, window.getStartCron());
            ps.setString(5, window.getEndCron());
        });
        return new TunerWindow(id, window.getPoolName(), window.getPresetName(),
                window.getStartCron(), window.getEndCron());
    }

    /** {@inheritDoc} */
    @Override
    public void removeWindow(long id) {
        jdbc.update(render(sql.removeWindow()), ps -> ps.setLong(1, id));
    }

    /** {@inheritDoc} */
    @Override
    public List<TunerWindow> windows(String poolName) {
        return jdbc.query(render(sql.listWindows()),
                ps -> {
                    ps.setString(1, poolName); // ? IS NULL 动态过滤（参数化非拼接）
                    ps.setString(2, poolName);
                }, WINDOW_MAPPER);
    }

    /** {@inheritDoc} */
    @Override
    public void appendAudit(TunerAuditEntry entry) {
        jdbc.update(render(sql.appendAudit()), ps -> {
            ps.setLong(1, idGenerator.getAsLong());
            ps.setTimestamp(2, Timestamp.valueOf(entry.getOccurredAt()));
            ps.setString(3, entry.getHost());
            ps.setString(4, entry.getPoolName());
            ps.setString(5, entry.getPresetName());
            ps.setString(6, entry.getSource().name());
            ps.setInt(7, entry.getOldCore());
            ps.setInt(8, entry.getOldMax());
            ps.setInt(9, entry.getOldKeep());
            ps.setInt(10, entry.getNewCore());
            ps.setInt(11, entry.getNewMax());
            ps.setInt(12, entry.getNewKeep());
        });
    }

    /** {@inheritDoc} */
    @Override
    public List<TunerAuditEntry> recentAudits(int limit) {
        return jdbc.query(render(sql.recentAudits()),
                ps -> ps.setInt(1, Math.max(1, limit)), AUDIT_MAPPER);
    }

    /** {@inheritDoc} */
    @Override
    public long versionStamp() {
        Timestamp stamp = jdbc.queryForObject(render(sql.versionStamp()), Timestamp.class);
        return stamp == null ? 0L : stamp.getTime();
    }

    /** {@inheritDoc} */
    @Override
    public long countBindings() {
        Long n = jdbc.queryForObject(render(sql.countBindings()), Long.class);
        return n == null ? 0 : n;
    }

    /** 表名占位渲染（前缀已构造期白名单校验） */
    private String render(String stmt) {
        return stmt.replace("{presetTable}", presetTable)
                .replace("{windowTable}", windowTable)
                .replace("{bindingTable}", bindingTable)
                .replace("{auditTable}", auditTable);
    }

    /** Timestamp → LocalDateTime（null 安全） */
    private static LocalDateTime toTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
