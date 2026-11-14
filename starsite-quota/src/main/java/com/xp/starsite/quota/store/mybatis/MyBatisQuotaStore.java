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
package com.xp.starsite.quota.store.mybatis;

import com.xp.starsite.quota.api.QuotaBalance;
import com.xp.starsite.quota.clock.TimeSource;
import com.xp.starsite.quota.store.QuotaStore;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import org.apache.ibatis.exceptions.PersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MyBatis 配额存储（消费方 classpath 自带 mybatis 时的零配置路径）：
 * 语句全部在 {@link QuotaAccountMapper} XML（同包资源，D3 决策）。
 *
 * <p>与 {@code JdbcQuotaStore} 语义等价（对齐测试互证）；差异仅在插入冲突翻译：
 * MyBatis 无 Spring 异常翻译——INSERT 异常后回查键确认存在才判"已存在"
 * （否则原样重抛，不吞真故障）。
 *
 * @author muyi
 * @date 2026/11/14
 */
public class MyBatisQuotaStore implements QuotaStore {

    private static final Logger log = LoggerFactory.getLogger(MyBatisQuotaStore.class);

    /**
     * 表结构契约列（BUG-009 结构校验）：与 DDL 文件列集一致，
     * 与 JdbcQuotaStore 保持同一契约集。
     */
    private static final Set<String> REQUIRED_COLUMNS = Set.of(
            "ID", "QUOTA_KEY", "TOTAL_AMOUNT", "OCCUPIED", "FROZEN", "GMT_CREATE", "GMT_MODIFIED");

    /** 表名白名单（仅字母数字下划线）：${table} 字符串替换的唯一防注入手段 */
    private static final java.util.regex.Pattern SAFE_IDENTIFIER =
            java.util.regex.Pattern.compile("[A-Za-z0-9_]+");

    /** 结构校验只做一次（幂等标志） */
    private volatile boolean schemaVerified = false;

    /** MyBatis Mapper（MyBatis 生态的 SQL 载体） */
    private final QuotaAccountMapper mapper;

    /** 配额表名（已过白名单校验） */
    private final String table;

    /** 主键 id 生成器（starsite-sequence 组件——组件表物理主键标准来源） */
    private final LongSupplier idGenerator;

    /** 时间源（建户时间戳；测试注入假时钟） */
    private final TimeSource time;

    /**
     * 便捷构造（系统时钟）。
     *
     * @param mapper      MyBatis Mapper
     * @param tableName   表名（仅字母数字下划线）
     * @param initDdl     true 时构造期自动建表 + 结构校验
     * @param idGenerator 主键 id 生成器（sequence 组件）
     * @throws IllegalArgumentException 表名为 null 或含白名单外字符
     */
    public MyBatisQuotaStore(QuotaAccountMapper mapper, String tableName, boolean initDdl,
                             LongSupplier idGenerator) {
        this(mapper, tableName, initDdl, idGenerator, TimeSource.system());
    }

    /**
     * 全参构造（显式时间源——测试注入假时钟）。
     *
     * @param mapper      MyBatis Mapper
     * @param tableName   表名（白名单校验）
     * @param initDdl     true 时构造期建表 + 结构校验
     * @param idGenerator 主键 id 生成器
     * @param time        时间源
     * @throws IllegalArgumentException 表名为 null 或含白名单外字符
     */
    public MyBatisQuotaStore(QuotaAccountMapper mapper, String tableName, boolean initDdl,
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
     * {@inheritDoc} INSERT 异常后回查确认：同键行已存在 = uk 冲突（保留现量返回账面）；
     * 行不存在 = 真故障，原样重抛（不吞——静默会破坏建户互斥）。
     */
    @Override
    public QuotaBalance init(String quotaKey, long total) {
        try {
            mapper.insert(table, idGenerator.getAsLong(), quotaKey, total, time.now());
            return new QuotaBalance(quotaKey, total, 0, 0);
        } catch (PersistenceException e) {
            QuotaBalance existing = mapper.findByKey(table, quotaKey);
            if (existing != null) {
                return existing;
            }
            throw e;
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<QuotaBalance> find(String quotaKey) {
        return Optional.ofNullable(mapper.findByKey(table, quotaKey));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<QuotaBalance> occupy(String quotaKey, long amount) {
        return mutate(mapper.occupy(table, quotaKey, amount), quotaKey);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<QuotaBalance> release(String quotaKey, long amount) {
        return mutate(mapper.release(table, quotaKey, amount), quotaKey);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<QuotaBalance> freeze(String quotaKey, long amount) {
        return mutate(mapper.freeze(table, quotaKey, amount), quotaKey);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<QuotaBalance> unfreeze(String quotaKey, long amount) {
        return mutate(mapper.unfreeze(table, quotaKey, amount), quotaKey);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<QuotaBalance> confirm(String quotaKey, long amount) {
        return mutate(mapper.confirm(table, quotaKey, amount), quotaKey);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<QuotaBalance> adjustTotal(String quotaKey, long delta) {
        return mutate(mapper.adjustTotal(table, quotaKey, delta), quotaKey);
    }

    /** {@inheritDoc} */
    @Override
    public long countAccounts() {
        return mapper.countAccounts(table);
    }

    /**
     * 条件更新结果翻译：影响行数 &gt; 0 → 重读账面；0 → empty（条件不中或键不存在）。
     *
     * @param updated  影响行数
     * @param quotaKey 配额键
     * @return 变更后账面；条件不中为 empty
     */
    private Optional<QuotaBalance> mutate(int updated, String quotaKey) {
        return updated > 0 ? find(quotaKey) : Optional.empty();
    }

    /**
     * 幂等建表（IF NOT EXISTS）+ 结构校验（BUG-009 同款语义：表存在但缺契约列
     * → 启动期 fail-fast 含迁移指引；表不存在只告警不阻断）。
     */
    private void ensureTable() {
        try {
            mapper.createTable(table);
            log.info("[starsite-quota] 表 {} 就绪（IF NOT EXISTS）", table);
        } catch (RuntimeException e) {
            log.warn("[starsite-quota] 建表检查失败（不阻断启动）: {}", e.getMessage());
        }
        verifyTableColumns();
    }

    /**
     * 结构校验（BUG-009）：表存在但缺契约列 → 抛错阻断启动。
     *
     * @throws IllegalStateException 表存在但结构陈旧，消息含迁移指引
     */
    private void verifyTableColumns() {
        if (schemaVerified) {
            return;
        }
        List<String> columns;
        try {
            columns = mapper.selectColumns(table);
        } catch (RuntimeException e) {
            log.warn("[starsite-quota] 结构校验查询失败（跳过校验，不阻断启动）: {}", e.getMessage());
            return;
        }
        schemaVerified = true;
        if (columns == null || columns.isEmpty()) {
            log.warn("[starsite-quota] 表 {} 不存在或不可见——视为 DBA/迁移脚本管理，跳过结构校验", table);
            return;
        }
        Set<String> actual = columns.stream().map(String::toUpperCase)
                .collect(java.util.stream.Collectors.toSet());
        var missing = REQUIRED_COLUMNS.stream().filter(c -> !actual.contains(c)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(("表 %s 已存在但缺少列 %s——结构陈旧。"
                    + "请按 resources/sql/mysql/quota_account_ddl.sql 迁移（ALTER 补列或 DROP 重建），组件不自动 ALTER")
                    .formatted(table, missing));
        }
        log.debug("[starsite-quota] 表 {} 结构校验通过（{} 列契约）", table, REQUIRED_COLUMNS.size());
    }
}
