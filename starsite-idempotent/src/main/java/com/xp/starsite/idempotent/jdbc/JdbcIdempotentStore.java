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
package com.xp.starsite.idempotent.jdbc;

import com.xp.starsite.idempotent.api.IdempotentRecord;
import com.xp.starsite.idempotent.api.IdempotentStatus;
import com.xp.starsite.idempotent.store.ClaimOutcome;
import com.xp.starsite.idempotent.store.IdempotentSql;
import com.xp.starsite.idempotent.store.IdempotentStore;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collections;
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
 * JDBC 幂等存储（MySQL 方言默认；语句全部来自 {@link IdempotentSql}——Java 零 SQL 字符串）。
 * id 由 sequence 组件预生成（LongSupplier 注入，与 async-command/schedule 同形）。
 *
 * <p>认领互斥依赖 uk(scope, idem_key)：INSERT 冲突经 Spring 异常翻译为
 * {@link DuplicateKeyException} 即 duplicate 结局；回收互斥依赖单条条件 UPDATE 原子性。
 *
 * @author muyi
 * @date 2026/11/12
 */
public class JdbcIdempotentStore implements IdempotentStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcIdempotentStore.class);

    /**
     * 表结构契约列（BUG-009 结构校验）：表存在但缺任一列 = 陈旧结构 → 启动期 fail-fast，
     * 防每次幂等请求以 bad SQL grammar 失败（与 DDL 文件列集一致，
     * 与 MyBatisIdempotentStore 保持同一契约集）。
     */
    private static final Set<String> REQUIRED_COLUMNS = Set.of(
            "ID", "SCOPE", "IDEM_KEY", "STATUS", "RESULT", "ERROR", "EXPIRE_AT",
            "FINISHED_AT", "HOST", "GMT_CREATE", "GMT_MODIFIED");

    /** 表名白名单（仅字母数字下划线）：表名须拼接进 SQL 无法参数化，白名单校验是防注入的唯一手段 */
    private static final java.util.regex.Pattern SAFE_IDENTIFIER =
            java.util.regex.Pattern.compile("[A-Za-z0-9_]+");

    /** 结构校验只做一次（幂等标志） */
    private volatile boolean schemaVerified = false;

    /** JDBC 模板（无状态，线程安全） */
    private final JdbcTemplate jdbc;

    /** 幂等表名（已过白名单校验） */
    private final String table;

    /** SQL 方言（语句全部来自这里，本类零硬编码 SQL） */
    private final IdempotentSql sql;

    /** 主键 id 生成器（starsite-sequence 组件的 next()——组件表物理主键标准来源） */
    private final LongSupplier idGenerator;

    /** 行映射（详情全列——本组件无列表查询，点查即详情） */
    private static final RowMapper<IdempotentRecord> MAPPER = (rs, i) -> new IdempotentRecord(
            rs.getLong("id"), rs.getString("scope"), rs.getString("idem_key"),
            IdempotentStatus.fromCode(rs.getString("status")), rs.getString("result"),
            rs.getString("error"), toTime(rs.getTimestamp("expire_at")),
            toTime(rs.getTimestamp("finished_at")), rs.getString("host"),
            toTime(rs.getTimestamp("gmt_create")), toTime(rs.getTimestamp("gmt_modified")));

    /**
     * 便捷构造（MySQL 默认方言 + 构造期建表）。
     *
     * @param jdbc        JdbcTemplate
     * @param table       表名（[A-Za-z0-9_] 白名单）
     * @param idGenerator id 生成器（sequence 组件）
     */
    public JdbcIdempotentStore(JdbcTemplate jdbc, String table, LongSupplier idGenerator) {
        this(jdbc, table, new IdempotentSql.MySql(), true, idGenerator);
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
    public JdbcIdempotentStore(JdbcTemplate jdbc, String table, IdempotentSql sql,
                               boolean initDdl, LongSupplier idGenerator) {
        if (table == null || !SAFE_IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("表名仅允许字母数字下划线: " + table);
        }
        this.jdbc = jdbc;
        this.table = table;
        this.sql = sql;
        this.idGenerator = idGenerator;
        if (initDdl) {
            ensureTable();
        }
    }

    /** {@inheritDoc} uk 冲突（DuplicateKeyException）→ duplicate；其余异常向上传播（fail-fast） */
    @Override
    public ClaimOutcome tryClaim(String scope, String idemKey, LocalDateTime expireAt, String host) {
        long id = idGenerator.getAsLong();
        try {
            jdbc.update(render(sql.insert()), ps -> {
                ps.setLong(1, id);
                ps.setString(2, scope == null ? "" : scope);
                ps.setString(3, idemKey);
                ps.setString(4, IdempotentStatus.PROCESSING.name());
                ps.setTimestamp(5, Timestamp.valueOf(expireAt));
                ps.setString(6, host);
            });
        } catch (DuplicateKeyException e) {
            return ClaimOutcome.duplicate();
        }
        return ClaimOutcome.claimed(new IdempotentRecord(id, scope == null ? "" : scope, idemKey,
                IdempotentStatus.PROCESSING, null, null, expireAt, null, host, null, null));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<IdempotentRecord> find(String scope, String idemKey) {
        List<IdempotentRecord> rows = jdbc.query(render(sql.findByKey()),
                ps -> {
                    ps.setString(1, scope == null ? "" : scope);
                    ps.setString(2, idemKey);
                }, MAPPER);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** {@inheritDoc} 回收成功后重读行（拿新 expire_at 与 gmt_modified——判定与留痕都要） */
    @Override
    public Optional<IdempotentRecord> tryReclaim(String scope, String idemKey,
                                                 LocalDateTime expireAt, LocalDateTime now,
                                                 LocalDateTime staleBefore, String host) {
        String normalized = scope == null ? "" : scope;
        int updated = jdbc.update(render(sql.reclaim()), ps -> {
            ps.setTimestamp(1, Timestamp.valueOf(expireAt));
            ps.setString(2, host);
            ps.setString(3, normalized);
            ps.setString(4, idemKey);
            ps.setTimestamp(5, Timestamp.valueOf(now));
            ps.setTimestamp(6, Timestamp.valueOf(staleBefore));
        });
        return updated > 0 ? find(normalized, idemKey) : Optional.empty();
    }

    /** {@inheritDoc} 双条件守卫（PROCESSING + 本代 expire_at）——代际令牌语义见接口 doc */
    @Override
    public boolean complete(String scope, String idemKey, LocalDateTime claimExpireAt, String result) {
        String normalized = scope == null ? "" : scope;
        return jdbc.update(render(sql.complete()), ps -> {
            ps.setString(1, result);
            ps.setString(2, normalized);
            ps.setString(3, idemKey);
            ps.setTimestamp(4, Timestamp.valueOf(claimExpireAt));
        }) > 0;
    }

    /** {@inheritDoc} */
    @Override
    public boolean fail(String scope, String idemKey, LocalDateTime claimExpireAt, String error) {
        String normalized = scope == null ? "" : scope;
        return jdbc.update(render(sql.fail()), ps -> {
            ps.setString(1, error);
            ps.setString(2, normalized);
            ps.setString(3, idemKey);
            ps.setTimestamp(4, Timestamp.valueOf(claimExpireAt));
        }) > 0;
    }

    /** {@inheritDoc} */
    @Override
    public long countByStatus(IdempotentStatus status) {
        Long n = jdbc.queryForObject(render(sql.countByStatus()), Long.class, status.name());
        return n == null ? 0 : n;
    }

    /**
     * {@inheritDoc} 两步法批量删除（DELETE..LIMIT 与 UPDATE..LIMIT 同为分片红线
     * ERROR 20046，OBS-111 同源）：SELECT 过期 id LIMIT → IN 删除，
     * 全程参数绑定（IN 占位符按选中条数生成，不拼接值）。
     */
    @Override
    public int purgeExpired(int batchSize, LocalDateTime now) {
        List<Long> expiredIds = jdbc.queryForList(render(sql.selectExpiredIds()),
                Long.class, now, Math.max(1, batchSize));
        if (expiredIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", Collections.nCopies(expiredIds.size(), "?"));
        return jdbc.update(render(sql.deleteByIds()).replace("{idPlaceholders}", placeholders),
                expiredIds.toArray());
    }

    /**
     * 自动建表（幂等 IF NOT EXISTS）+ 结构校验。建表失败只记 warn 不抛出：
     * 表可能由 DBA/迁移脚本统一管理；但表<b>存在</b>而结构陈旧（缺契约列）时抛错阻断——
     * 否则每次幂等请求都以 bad SQL grammar 失败（BUG-009 同款实证语义）。
     */
    private void ensureTable() {
        try {
            jdbc.execute(render(sql.createTable()));
            log.info("[starsite-idempotent] 表 {} 就绪（IF NOT EXISTS）", table);
        } catch (Exception e) {
            // 建表失败不阻断启动（表可能由 DBA/迁移脚本管理），首条认领时即会暴露
            log.warn("[starsite-idempotent] 建表检查失败（不阻断启动）: {}", e.getMessage());
        }
        verifyTableColumns();
    }

    /**
     * 结构校验（BUG-009）：表<b>存在</b>但缺契约列 → 抛错阻断启动（fail-fast）；
     * 表不存在或校验失败 → 只告警不阻断（表交由 DBA/迁移脚本管理，建表 DDL 自会创建全列）。
     * 实现走 {@link DatabaseMetaData#getColumns}（JDBC 标准元数据 API）：
     * 零 SQL 语句、天然参数化；表名匹配先按原名再按大写（H2 未加引号标识符存大写）。
     *
     * @throws IllegalStateException 表存在但结构陈旧（缺契约列），消息含迁移指引
     */
    private void verifyTableColumns() {
        if (schemaVerified) {
            return;
        }
        javax.sql.DataSource ds = jdbc.getDataSource();
        if (ds == null) {
            log.warn("[starsite-idempotent] JdbcTemplate 无 DataSource——跳过结构校验");
            return;
        }
        Set<String> actual = new HashSet<>();
        try (Connection conn = ds.getConnection()) {
            collectColumnNames(conn.getMetaData(), table, actual);
            if (actual.isEmpty()) {
                collectColumnNames(conn.getMetaData(), table.toUpperCase(), actual);
            }
        } catch (Exception e) {
            log.warn("[starsite-idempotent] 结构校验查询失败（跳过校验，不阻断启动）: {}", e.getMessage());
            return;
        }
        schemaVerified = true;
        if (actual.isEmpty()) {
            log.warn("[starsite-idempotent] 表 {} 不存在或不可见——视为 DBA/迁移脚本管理，跳过结构校验", table);
            return;
        }
        var missing = REQUIRED_COLUMNS.stream().filter(c -> !actual.contains(c)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(("表 %s 已存在但缺少列 %s——结构陈旧（旧版 DDL 初始化或组件升级遗留）。"
                    + "请按 resources/sql/mysql/idempotent_record_ddl.sql 迁移（ALTER 补列或 DROP 重建），组件不自动 ALTER")
                    .formatted(table, missing));
        }
        log.debug("[starsite-idempotent] 表 {} 结构校验通过（{} 列契约）", table, REQUIRED_COLUMNS.size());
    }

    /**
     * 经 JDBC 元数据 API 收集表的列名（统一大写便于大小写无关比对）。
     *
     * @param meta  数据库元数据
     * @param table 表名（原样传入，由调用方负责大小写兜底重试）
     * @param out   收集目标（追加写入）
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

    /** Timestamp → LocalDateTime（null 安全） */
    private static LocalDateTime toTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
