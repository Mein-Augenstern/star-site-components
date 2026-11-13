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
package com.xp.starsite.idempotent.mybatis;

import com.xp.starsite.idempotent.api.IdempotentRecord;
import com.xp.starsite.idempotent.api.IdempotentStatus;
import com.xp.starsite.idempotent.clock.TimeSource;
import com.xp.starsite.idempotent.store.ClaimOutcome;
import com.xp.starsite.idempotent.store.IdempotentStore;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import org.apache.ibatis.exceptions.PersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MyBatis 幂等存储（消费方 classpath 自带 mybatis 时的零配置路径）：
 * 语句全部在 {@link IdempotentRecordMapper} XML（同包资源，D3 决策）。
 *
 * <p>与 {@code JdbcIdempotentStore} 语义等价（等价测试互证，reviewer 第 5 尺）；
 * 差异仅在认领冲突的翻译：MyBatis 无 Spring 异常翻译——INSERT 异常后回查键
 * 确认存在才判 duplicate（否则原样重抛，不吞真故障）。
 *
 * @author muyi
 * @date 2026/11/12
 */
public class MyBatisIdempotentStore implements IdempotentStore {

    private static final Logger log = LoggerFactory.getLogger(MyBatisIdempotentStore.class);

    /**
     * 表结构契约列（BUG-009 结构校验）：表存在但缺任一列 = 陈旧结构 → 启动期 fail-fast
     * （与 DDL 文件列集一致，与 JdbcIdempotentStore 保持同一契约集）。
     */
    private static final Set<String> REQUIRED_COLUMNS = Set.of(
            "ID", "SCOPE", "IDEM_KEY", "STATUS", "RESULT", "ERROR", "EXPIRE_AT",
            "FINISHED_AT", "HOST", "GMT_CREATE", "GMT_MODIFIED");

    /** 表名白名单（仅字母数字下划线）：${table} 字符串替换的唯一防注入手段 */
    private static final java.util.regex.Pattern SAFE_IDENTIFIER =
            java.util.regex.Pattern.compile("[A-Za-z0-9_]+");

    /** 结构校验只做一次（幂等标志） */
    private volatile boolean schemaVerified = false;

    /** MyBatis Mapper（MyBatis 生态的 SQL 载体） */
    private final IdempotentRecordMapper mapper;

    /** 幂等表名（已过白名单校验） */
    private final String table;

    /** 主键 id 生成器（starsite-sequence 组件——组件表物理主键标准来源） */
    private final LongSupplier idGenerator;

    /** 时间源（purgeExpired 过期判定基准；测试注入假时钟推窗口） */
    private final TimeSource time;

    /**
     * 便捷构造（系统时钟）。
     *
     * @param mapper      MyBatis Mapper
     * @param tableName   表名（仅字母数字下划线）
     * @param initDdl     true 时构造期自动建表（失败只记 warn 不阻断——表可能由 DBA/迁移脚本管理）
     * @param idGenerator 主键 id 生成器（sequence 组件的 next()）
     * @throws IllegalArgumentException 表名为 null 或含白名单外字符
     */
    public MyBatisIdempotentStore(IdempotentRecordMapper mapper, String tableName, boolean initDdl,
                                  LongSupplier idGenerator) {
        this(mapper, tableName, initDdl, idGenerator, TimeSource.system());
    }

    /**
     * 全参构造（显式时间源——测试注入假时钟推过期窗口）。
     *
     * @param mapper      MyBatis Mapper
     * @param tableName   表名（仅字母数字下划线）
     * @param initDdl     true 时构造期自动建表
     * @param idGenerator 主键 id 生成器
     * @param time        时间源（过期判定基准）
     * @throws IllegalArgumentException 表名为 null 或含白名单外字符
     */
    public MyBatisIdempotentStore(IdempotentRecordMapper mapper, String tableName, boolean initDdl,
                                  LongSupplier idGenerator, TimeSource time) {
        if (tableName == null || !SAFE_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("表名仅允许字母数字下划线: " + tableName);
        }
        this.mapper = mapper;
        this.table = tableName;
        this.idGenerator = idGenerator;
        this.time = time;
        if (initDdl) {
            ensureTable();
        }
    }

    /**
     * {@inheritDoc} INSERT 异常后回查确认：同键行已存在 = uk 冲突（duplicate）；
     * 行不存在 = 真故障，原样重抛（不吞——静默降级会破坏恰一执行语义）。
     */
    @Override
    public ClaimOutcome tryClaim(String scope, String idemKey, LocalDateTime expireAt, String host) {
        String normalized = scope == null ? "" : scope;
        IdempotentRecord row = new IdempotentRecord(idGenerator.getAsLong(), normalized, idemKey,
                IdempotentStatus.PROCESSING, null, null, expireAt, null, host, null, null);
        try {
            mapper.insert(table, row);
            return ClaimOutcome.claimed(row);
        } catch (PersistenceException e) {
            if (mapper.findByKey(table, normalized, idemKey) != null) {
                return ClaimOutcome.duplicate();
            }
            throw e;
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<IdempotentRecord> find(String scope, String idemKey) {
        IdempotentRecord row = mapper.findByKey(table, scope == null ? "" : scope, idemKey);
        return Optional.ofNullable(row);
    }

    /** {@inheritDoc} 回收成功后重读行（拿新 expire_at 与 gmt_modified） */
    @Override
    public Optional<IdempotentRecord> tryReclaim(String scope, String idemKey,
                                                 LocalDateTime expireAt, LocalDateTime now,
                                                 LocalDateTime staleBefore, String host) {
        String normalized = scope == null ? "" : scope;
        int updated = mapper.reclaim(table, normalized, idemKey, expireAt, host, now, staleBefore);
        return updated > 0 ? find(normalized, idemKey) : Optional.empty();
    }

    /** {@inheritDoc} 双条件守卫（PROCESSING + 本代 expire_at）——代际令牌语义见接口 doc */
    @Override
    public boolean complete(long id, LocalDateTime claimExpireAt, String result) {
        return mapper.complete(table, id, claimExpireAt, result) > 0;
    }

    /** {@inheritDoc} */
    @Override
    public boolean fail(long id, LocalDateTime claimExpireAt, String error) {
        return mapper.fail(table, id, claimExpireAt, error) > 0;
    }

    /** {@inheritDoc} */
    @Override
    public long countByStatus(IdempotentStatus status) {
        return mapper.countByStatus(table, status.name());
    }

    /**
     * {@inheritDoc} 两步法批量删除（圈主键 → foreach IN 删除——占位符由 MyBatis 生成，
     * 全程参数绑定，无 DELETE..LIMIT 分片红线）。
     */
    @Override
    public int purgeExpired(int batchSize, LocalDateTime now) {
        List<Long> ids = mapper.selectExpiredIds(table, now, Math.max(1, batchSize));
        if (ids.isEmpty()) {
            return 0;
        }
        return mapper.deleteByIds(table, ids);
    }

    /**
     * 幂等建表（IF NOT EXISTS）+ 结构校验。建表失败只记 warn 不抛出——与 JdbcIdempotentStore
     * 同语义（表可能由 DBA/迁移脚本统一管理）；但表<b>存在</b>而结构陈旧（缺契约列）时
     * 抛错阻断——否则每次幂等请求都以 bad SQL grammar 失败（BUG-009 实证语义）。
     */
    private void ensureTable() {
        try {
            mapper.createTable(table);
            log.info("[starsite-idempotent] 表 {} 就绪（IF NOT EXISTS）", table);
        } catch (RuntimeException e) {
            log.warn("[starsite-idempotent] 建表检查失败（不阻断启动）: {}", e.getMessage());
        }
        verifyTableColumns();
    }

    /**
     * 结构校验（BUG-009）：表<b>存在</b>但缺契约列 → 抛错阻断启动（fail-fast）；
     * 表不存在或校验查询失败 → 只告警不阻断（表交由 DBA/迁移脚本管理，建表 DDL 自会创建全列）。
     * 校验只做一次（幂等）；表名参数绑定 + 大小写无关比对。
     *
     * @throws IllegalStateException 表存在但结构陈旧（缺契约列），消息含迁移指引
     */
    private void verifyTableColumns() {
        if (schemaVerified) {
            return;
        }
        List<String> columns;
        try {
            columns = mapper.selectColumns(table);
        } catch (RuntimeException e) {
            log.warn("[starsite-idempotent] 结构校验查询失败（跳过校验，不阻断启动）: {}", e.getMessage());
            return;
        }
        schemaVerified = true;
        if (columns == null || columns.isEmpty()) {
            log.warn("[starsite-idempotent] 表 {} 不存在或不可见——视为 DBA/迁移脚本管理，跳过结构校验", table);
            return;
        }
        Set<String> actual = columns.stream().map(String::toUpperCase)
                .collect(java.util.stream.Collectors.toSet());
        var missing = REQUIRED_COLUMNS.stream().filter(c -> !actual.contains(c)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(("表 %s 已存在但缺少列 %s——结构陈旧（旧版 DDL 初始化或组件升级遗留）。"
                    + "请按 resources/sql/mysql/idempotent_record_ddl.sql 迁移（ALTER 补列或 DROP 重建），组件不自动 ALTER")
                    .formatted(table, missing));
        }
        log.debug("[starsite-idempotent] 表 {} 结构校验通过（{} 列契约）", table, REQUIRED_COLUMNS.size());
    }
}
