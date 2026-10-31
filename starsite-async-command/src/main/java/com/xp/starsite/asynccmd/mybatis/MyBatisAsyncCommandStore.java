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
package com.xp.starsite.asynccmd.mybatis;

import com.xp.starsite.asynccmd.model.AsyncCommand;
import com.xp.starsite.asynccmd.model.CommandStatus;
import com.xp.starsite.asynccmd.store.AsyncCommandStore;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MyBatis 版命令存储：语义与 JdbcAsyncCommandStore 完全等价（同一套认领/回收/退避/分页），
 * SQL 落在与 Mapper 接口同包的 {@code AsyncCommandMapper.xml}（MyBatis 命名空间自动发现）——消费方的 MyBatis 生态
 * （拦截器、分页插件、SQL 日志）原生生效。
 * <p>
 * 表名经白名单校验后以 {@code ${table}} 字符串替换传入 Mapper（MyBatis 的 ${} 语义），
 * 业务值全部 {@code #{}} 参数绑定——与手工拼 SQL 的安全边界一致。
 * <p>
 * 状态列映射：Mapper 读写 String code，本类负责 String ↔
 * {@link CommandStatus} 枚举互转（Mapper 保持纯净表映射，领域逻辑在 Store 层）。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class MyBatisAsyncCommandStore implements AsyncCommandStore {

    /** 日志（slf4j） */
    private static final Logger log = LoggerFactory.getLogger(MyBatisAsyncCommandStore.class);

    /**
     * 表结构契约列（BUG-009 结构校验）：表存在但缺任一列 = 陈旧结构 → 启动期 fail-fast，
     * 防调度器每轮 tick 以 bad SQL grammar 失败且命令永久积压（与 DDL 文件列集一致，
     * 与 JdbcAsyncCommandStore 保持同一契约集）。
     */
    private static final java.util.Set<String> REQUIRED_COLUMNS = java.util.Set.of(
            "ID", "CMD_TYPE", "BIZ_KEY", "PAYLOAD", "STATUS", "MAX_RETRY", "RETRY_COUNT",
            "NEXT_RUN_AT", "LAST_ERROR", "OWNER", "CLAIM_TOKEN", "GMT_CREATE", "GMT_MODIFIED");

    /** 结构校验只做一次（幂等标志） */
    private volatile boolean schemaVerified = false;

    /** 表名白名单（仅字母数字下划线）：${table} 字符串替换的唯一防注入手段 */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    /** MyBatis Mapper（MyBatis 生态的 SQL 载体） */
    private final AsyncCommandMapper mapper;

    /** 命令表名（已过白名单校验） */
    private final String table;

    /** 是否构造期自动建表（MySQL 方言 DDL；生产建议迁移脚本管理） */
    private final boolean initDdl;

    /** 主键 id 生成器（starsite-sequence 组件——组件表物理主键标准来源） */
    private final LongSupplier idGenerator;

    /** 时间源（悬挂回收阈值计算；测试注入假时钟——与 JdbcAsyncCommandStore 同源，8de4ac8 对齐） */
    private final com.xp.starsite.asynccmd.clock.TimeSource time;

    /**
     * 便捷构造（系统时钟）。
     *
     * @param mapper  MyBatis Mapper
     * @param tableName 命令表名（仅字母数字下划线）
     * @param initDdl     true 时构造期自动建表（失败只记 warn 不阻断——表可能由 DBA/迁移脚本管理）
     * @param idGenerator 主键 id 生成器（sequence 组件的 next()；insert 前为命令分配物理主键）
     * @throws IllegalArgumentException 表名为 null 或含白名单外字符
     */
    public MyBatisAsyncCommandStore(AsyncCommandMapper mapper, String tableName, boolean initDdl,
                                    LongSupplier idGenerator) {
        this(mapper, tableName, initDdl, idGenerator,
                com.xp.starsite.asynccmd.clock.TimeSource.system());
    }

    /**
     * 全参构造（显式时间源——测试注入假时钟构造悬挂场景）。
     *
     * @param mapper  MyBatis Mapper
     * @param tableName 命令表名（仅字母数字下划线）
     * @param initDdl     true 时构造期自动建表（失败只记 warn 不阻断——表可能由 DBA/迁移脚本管理）
     * @param idGenerator 主键 id 生成器（sequence 组件的 next()；insert 前为命令分配物理主键）
     * @param time      时间源（reclaimStale 阈值计算；与调度器时钟同源）
     * @throws IllegalArgumentException 表名为 null 或含白名单外字符
     */
    public MyBatisAsyncCommandStore(AsyncCommandMapper mapper, String tableName, boolean initDdl,
                                    LongSupplier idGenerator,
                                    com.xp.starsite.asynccmd.clock.TimeSource time) {
        if (tableName == null || !SAFE_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("tableName 仅允许字母数字下划线: " + tableName);
        }
        this.mapper = mapper;
        this.table = tableName;
        this.initDdl = initDdl;
        this.idGenerator = idGenerator;
        this.time = time;
        if (initDdl) {
            ensureTable();
        }
    }

    /**
     * 幂等建表（IF NOT EXISTS）+ 结构校验。建表失败只记 warn 不抛出——与 JdbcAsyncCommandStore
     * 同语义（表可能由 DBA/迁移脚本统一管理）；但表<b>存在</b>而结构陈旧（缺契约列）时
     * 抛错阻断——否则调度器每轮 tick 都以 bad SQL grammar 失败且命令永久积压（BUG-009 实证）。
     */
    private void ensureTable() {
        try {
            mapper.createTable(table);
            log.info("[starsite-async-command] 表 {} 就绪（IF NOT EXISTS）", table);
        } catch (RuntimeException e) {
            // 建表失败不阻断启动（表可能由 DBA/迁移脚本管理），首条命令提交时即会暴露
            log.warn("[starsite-async-command] 建表检查失败（不阻断启动）: {}", e.getMessage());
        }
        verifyTableColumns();
    }

    /**
     * 结构校验（BUG-009）：表<b>存在</b>但缺契约列 → 抛错阻断启动（fail-fast）；
     * 表不存在或校验查询失败 → 只告警不阻断（表交由 DBA/迁移脚本管理，建表 DDL 自会创建全列）。
     * 校验只做一次（幂等）；表名经白名单校验，元数据查询全程 #{} 参数绑定。
     *
     * @throws IllegalStateException 表存在但结构陈旧（缺契约列），消息含迁移指引
     */
    private void verifyTableColumns() {
        if (schemaVerified) {
            return;
        }
        java.util.List<String> columns;
        try {
            columns = mapper.selectColumns(table);
        } catch (RuntimeException e) {
            log.warn("[starsite-async-command] 结构校验查询失败（跳过校验，不阻断启动）: {}", e.getMessage());
            return;
        }
        schemaVerified = true;
        if (columns == null || columns.isEmpty()) {
            log.warn("[starsite-async-command] 表 {} 不存在或不可见——视为 DBA/迁移脚本管理，跳过结构校验", table);
            return;
        }
        java.util.Set<String> actual = columns.stream()
                .map(String::toUpperCase)
                .collect(java.util.stream.Collectors.toSet());
        var missing = REQUIRED_COLUMNS.stream().filter(c -> !actual.contains(c)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(("表 %s 已存在但缺少列 %s——结构陈旧（旧版 DDL 初始化或组件升级遗留）。"
                    + "请按 resources/sql/mysql/async_command_ddl.sql 迁移（ALTER 补列或 DROP 重建），组件不自动 ALTER")
                    .formatted(table, missing));
        }
        log.debug("[starsite-async-command] 表 {} 结构校验通过（{} 列契约）", table, REQUIRED_COLUMNS.size());
    }

    /** {@inheritDoc} */
    @Override
    public void insert(AsyncCommand cmd) {
        // 组件表物理主键标准来源：starsite-sequence 组件（表无 AUTO_INCREMENT）
        if (cmd.getId() == null) {
            cmd.setId(idGenerator.getAsLong());
        }
        mapper.insert(table, cmd);
    }

    /** {@inheritDoc} 状态 code 经 {@link CommandStatus#fromCode} 解析为枚举 */
    @Override
    public AsyncCommand findById(long id) {
        return toDomain(mapper.selectById(table, id));
    }

    /** {@inheritDoc} */
    @Override
    public long countUnfinished(String cmdType, String bizKey) {
        return mapper.countUnfinished(table, cmdType, bizKey == null ? "" : bizKey);
    }

    /** {@inheritDoc} */
    @Override
    public int claim(String token, int limit, LocalDateTime now) {
        return mapper.claim(table, token, now, limit);
    }

    /** {@inheritDoc} */
    @Override
    public List<AsyncCommand> findByToken(String token) {
        return mapper.findByToken(table, token).stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public boolean claimById(long id, String token) {
        return mapper.claimById(table, token, id) > 0;
    }

    /** {@inheritDoc} 悬挂阈值由注入时钟计算（与 JdbcAsyncCommandStore 同源可测，8de4ac8 对齐） */
    @Override
    public int reclaimStale(int staleAfterSeconds, int limit) {
        return mapper.reclaimStale(table, time.now().minusSeconds(staleAfterSeconds), limit);
    }

    /** {@inheritDoc} */
    @Override
    public int updateState(AsyncCommand cmd) {
        return mapper.updateState(table, cmd);
    }

    /** {@inheritDoc} */
    @Override
    public long countByStatus(String status) {
        return mapper.countByStatus(table, status);
    }

    /** {@inheritDoc} */
    @Override
    public long countBacklogDue(LocalDateTime now) {
        return mapper.countBacklogDue(table, now);
    }

    /** {@inheritDoc} */
    @Override
    public List<AsyncCommand> selectRecent(int limit) {
        return mapper.selectRecent(table, limit).stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public long countPage(String status, String cmdType) {
        return mapper.countPage(table, blankToNull(status), blankToNull(cmdType));
    }

    /** {@inheritDoc} */
    @Override
    public List<AsyncCommand> selectPage(String status, String cmdType, long offset, int size) {
        return mapper.selectPage(table, blankToNull(status), blankToNull(cmdType), offset, size)
                .stream().map(this::toDomain).toList();
    }

    /**
     * 行实体的 String 状态列解析为枚举（MyBatis 驼峰映射后 status 字段为 String 兼容形态）。
     *
     * @param row Mapper 返回的行实体（可能为 null）
     * @return 解析后的同一实例；入参 null 返回 null
     */
    private AsyncCommand toDomain(AsyncCommand row) {
        if (row != null && row.getStatus() != null) {
            row.setStatus(row.getStatus()); // fromCode 解析（setStatus 内部走 CommandStatus.fromCode）
        }
        return row;
    }

    /**
     * 空/空白转 null（Mapper 动态 SQL 的 &lt;if&gt; 判空语义对齐）。
     *
     * @param s 原值
     * @return 非空白原值；否则 null
     */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
