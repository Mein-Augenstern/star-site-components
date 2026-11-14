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
package com.xp.starsite.quota.store.jdbc;

import com.xp.starsite.quota.api.QuotaBalance;
import com.xp.starsite.quota.store.QuotaSql;
import com.xp.starsite.quota.store.QuotaStore;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JDBC 配额存储（MySQL 方言默认；语句全部来自 {@link QuotaSql}——Java 零 SQL 字符串）。
 * id 由 sequence 组件预生成（LongSupplier 注入，家族同形）。
 *
 * <p>不变式正确性模型：五原子操作各是<b>单条条件 UPDATE</b>（不变式在 WHERE 内
 * 原子判定）——与 lock 的唯键单行 UPDATE、idempotent 的代际守卫同族；并发超量
 * 的操作影响行数为 0，即被拒绝，无读改写窗口。
 *
 * @author muyi
 * @date 2026/11/14
 */
public class JdbcQuotaStore implements QuotaStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcQuotaStore.class);

    /**
     * 表结构契约列（BUG-009 结构校验）：表存在但缺任一列 = 陈旧结构 → 启动期 fail-fast。
     */
    private static final Set<String> REQUIRED_COLUMNS = Set.of(
            "ID", "QUOTA_KEY", "TOTAL_AMOUNT", "OCCUPIED", "FROZEN", "GMT_CREATE", "GMT_MODIFIED");

    /** 表名白名单（仅字母数字下划线）：表名须拼接进 SQL 无法参数化，白名单校验是防注入的唯一手段 */
    private static final java.util.regex.Pattern SAFE_IDENTIFIER =
            java.util.regex.Pattern.compile("[A-Za-z0-9_]+");

    /** 结构校验只做一次（幂等标志） */
    private volatile boolean schemaVerified = false;

    /** JDBC 模板（无状态，线程安全） */
    private final JdbcTemplate jdbc;

    /** 配额表名（已过白名单校验） */
    private final String table;

    /** SQL 方言（语句全部来自这里，本类零硬编码 SQL） */
    private final QuotaSql sql;

    /** 主键 id 生成器（starsite-sequence 组件的 next()——组件表物理主键标准来源） */
    private final LongSupplier idGenerator;

    /** 时间源（插入时间戳参数绑定——与调用方时钟同源可测） */
    private final com.xp.starsite.quota.clock.TimeSource time;

    /** 行映射（账面五列） */
    private static final RowMapper<QuotaBalance> MAPPER = (rs, i) -> new QuotaBalance(
            rs.getString("quota_key"), rs.getLong("total_amount"),
            rs.getLong("occupied"), rs.getLong("frozen"));

    /**
     * 便捷构造（MySQL 默认方言 + 构造期建表）。
     *
     * @param jdbc        JdbcTemplate
     * @param table       表名（[A-Za-z0-9_] 白名单）
     * @param idGenerator id 生成器（sequence 组件）
     */
    public JdbcQuotaStore(JdbcTemplate jdbc, String table, LongSupplier idGenerator) {
        this(jdbc, table, new QuotaSql.MySql(), true, idGenerator);
    }

    /**
     * 全参构造。
     *
     * @param jdbc        JdbcTemplate
     * @param table       表名（[A-Za-z0-9_] 白名单，非法即抛——防 {table} 渲染注入）
     * @param sql         SQL 方言（自定义方言实现其他数据库适配）
     * @param initDdl     true 构造期自动建表（IF NOT EXISTS 幂等）+ 结构校验；false 交由迁移脚本管理
     * @param idGenerator id 生成器（sequence 组件）
     * @throws IllegalArgumentException 表名为 null 或含白名单外字符
     */
    public JdbcQuotaStore(JdbcTemplate jdbc, String table, QuotaSql sql, boolean initDdl,
                          LongSupplier idGenerator) {
        this(jdbc, table, sql, initDdl, idGenerator,
                com.xp.starsite.quota.clock.TimeSource.system());
    }

    /**
     * 全参构造（显式时间源——测试注入假时钟）。
     *
     * @param jdbc        JdbcTemplate
     * @param table       表名（白名单校验）
     * @param sql         SQL 方言
     * @param initDdl     true 构造期建表 + 结构校验
     * @param idGenerator id 生成器
     * @param time        时间源（插入时间戳）
     * @throws IllegalArgumentException 表名为 null 或含白名单外字符
     */
    public JdbcQuotaStore(JdbcTemplate jdbc, String table, QuotaSql sql, boolean initDdl,
                          LongSupplier idGenerator, com.xp.starsite.quota.clock.TimeSource time) {
        if (table == null || !SAFE_IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("表名仅允许字母数字下划线: " + table);
        }
        this.jdbc = jdbc;
        this.table = table;
        this.sql = sql;
        this.idGenerator = idGenerator;
        this.time = time;
        if (initDdl) {
            ensureTable();
        }
    }

    /** {@inheritDoc} uk 冲突（DuplicateKeyException）→ 已存在：保留现量返回账面 */
    @Override
    public QuotaBalance init(String quotaKey, long total) {
        try {
            jdbc.update(render(sql.insert()), ps -> {
                ps.setLong(1, idGenerator.getAsLong());
                ps.setString(2, quotaKey);
                ps.setLong(3, total);
                ps.setTimestamp(4, Timestamp.valueOf(time.now()));
            });
            return new QuotaBalance(quotaKey, total, 0, 0);
        } catch (DuplicateKeyException e) {
            return find(quotaKey).orElseThrow(); // uk 冲突则行必在
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<QuotaBalance> find(String quotaKey) {
        List<QuotaBalance> rows = jdbc.query(render(sql.findByKey()),
                ps -> ps.setString(1, quotaKey), MAPPER);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** {@inheritDoc} 条件不中（含账户不存在）影响行数 0 → empty；成功后重读账面 */
    @Override
    public Optional<QuotaBalance> occupy(String quotaKey, long amount) {
        // 参数序：(SET 增量, 键, 不变式条件增量)——与方言语句占位序逐条对齐
        return update(sql.occupy(), ps -> {
            ps.setLong(1, amount);
            ps.setString(2, quotaKey);
            ps.setLong(3, amount);
        }, quotaKey);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<QuotaBalance> release(String quotaKey, long amount) {
        return update(sql.release(), ps -> {
            ps.setLong(1, amount);
            ps.setString(2, quotaKey);
            ps.setLong(3, amount);
        }, quotaKey);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<QuotaBalance> freeze(String quotaKey, long amount) {
        return update(sql.freeze(), ps -> {
            ps.setLong(1, amount);
            ps.setString(2, quotaKey);
            ps.setLong(3, amount);
        }, quotaKey);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<QuotaBalance> unfreeze(String quotaKey, long amount) {
        return update(sql.unfreeze(), ps -> {
            ps.setLong(1, amount);
            ps.setString(2, quotaKey);
            ps.setLong(3, amount);
        }, quotaKey);
    }

    /** {@inheritDoc} 双计数换位（4 占位：SET×2 + 键 + 条件）成功后重读账面 */
    @Override
    public Optional<QuotaBalance> confirm(String quotaKey, long amount) {
        return update(sql.confirm(), ps -> {
            ps.setLong(1, amount);
            ps.setLong(2, amount);
            ps.setString(3, quotaKey);
            ps.setLong(4, amount);
        }, quotaKey);
    }

    /** {@inheritDoc} 降额保护条件在语句 WHERE 内 */
    @Override
    public Optional<QuotaBalance> adjustTotal(String quotaKey, long delta) {
        return update(sql.adjustTotal(), ps -> {
            ps.setLong(1, delta);
            ps.setString(2, quotaKey);
            ps.setLong(3, delta);
        }, quotaKey);
    }

    /** {@inheritDoc} */
    @Override
    public long countAccounts() {
        Long n = jdbc.queryForObject(render(sql.countAccounts()), Long.class);
        return n == null ? 0 : n;
    }

    /**
     * 条件更新执行：绑定由各操作显式给出（占位序契约随语句走），影响行数 0 = empty。
     *
     * @param stmt     方言语句
     * @param binder   参数绑定（占位序与语句逐条对齐）
     * @param quotaKey 成功后重读账面的键
     * @return 更新后账面；条件不中为 empty
     */
    private Optional<QuotaBalance> update(String stmt,
                                           org.springframework.jdbc.core.PreparedStatementSetter binder,
                                           String quotaKey) {
        int updated = jdbc.update(render(stmt), binder);
        return updated > 0 ? find(quotaKey) : Optional.empty();
    }

    /**
     * 自动建表（幂等 IF NOT EXISTS）+ 结构校验。建表失败只记 warn 不抛出：
     * 表可能由 DBA/迁移脚本统一管理；但表<b>存在</b>而结构陈旧（缺契约列）时
     * 抛错阻断（BUG-009 同款语义）。
     */
    private void ensureTable() {
        try {
            jdbc.execute(render(sql.createTable()));
            log.info("[starsite-quota] 表 {} 就绪（IF NOT EXISTS）", table);
        } catch (Exception e) {
            log.warn("[starsite-quota] 建表检查失败（不阻断启动）: {}", e.getMessage());
        }
        verifyTableColumns();
    }

    /**
     * 结构校验（BUG-009）：表存在但缺契约列 → 抛错阻断启动；
     * 表不存在或校验失败 → 只告警不阻断。走 {@link DatabaseMetaData#getColumns}。
     *
     * @throws IllegalStateException 表存在但结构陈旧，消息含迁移指引
     */
    private void verifyTableColumns() {
        if (schemaVerified) {
            return;
        }
        javax.sql.DataSource ds = jdbc.getDataSource();
        if (ds == null) {
            log.warn("[starsite-quota] JdbcTemplate 无 DataSource——跳过结构校验");
            return;
        }
        Set<String> actual = new HashSet<>();
        try (Connection conn = ds.getConnection()) {
            collectColumnNames(conn.getMetaData(), table, actual);
            if (actual.isEmpty()) {
                collectColumnNames(conn.getMetaData(), table.toUpperCase(), actual);
            }
        } catch (Exception e) {
            log.warn("[starsite-quota] 结构校验查询失败（跳过校验，不阻断启动）: {}", e.getMessage());
            return;
        }
        schemaVerified = true;
        if (actual.isEmpty()) {
            log.warn("[starsite-quota] 表 {} 不存在或不可见——视为 DBA/迁移脚本管理，跳过结构校验", table);
            return;
        }
        var missing = REQUIRED_COLUMNS.stream().filter(c -> !actual.contains(c)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(("表 %s 已存在但缺少列 %s——结构陈旧。"
                    + "请按 resources/sql/mysql/quota_account_ddl.sql 迁移（ALTER 补列或 DROP 重建），组件不自动 ALTER")
                    .formatted(table, missing));
        }
        log.debug("[starsite-quota] 表 {} 结构校验通过（{} 列契约）", table, REQUIRED_COLUMNS.size());
    }

    /**
     * 经 JDBC 元数据 API 收集表的列名（统一大写便于大小写无关比对）。
     *
     * @param meta  数据库元数据
     * @param table 表名
     * @param out   收集目标
     * @throws Exception 元数据访问失败
     */
    private static void collectColumnNames(DatabaseMetaData meta, String table, Set<String> out)
            throws Exception {
        try (ResultSet rs = meta.getColumns(null, null, table, null)) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                if (name != null) {
                    out.add(name.toUpperCase());
                }
            }
        }
    }

    /** {table} 占位渲染（表名已在构造期白名单校验） */
    private String render(String stmt) {
        return stmt.replace("{table}", table);
    }
}
