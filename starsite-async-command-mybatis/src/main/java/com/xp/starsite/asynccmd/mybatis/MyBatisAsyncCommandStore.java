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
import java.util.regex.Pattern;

/**
 * MyBatis 版命令存储：语义与 JdbcAsyncCommandStore 完全等价（同一套认领/回收/退避/分页），
 * SQL 落在 {@link AsyncCommandMapper} 的 MyBatis 注解里——消费方的 MyBatis 生态
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

    /** 表名白名单（仅字母数字下划线）：${table} 字符串替换的唯一防注入手段 */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    /** MyBatis Mapper（MyBatis 生态的 SQL 载体） */
    private final AsyncCommandMapper mapper;

    /** 命令表名（已过白名单校验） */
    private final String table;

    /** 是否构造期自动建表（H2 兼容 DDL；生产建议迁移脚本管理） */
    private final boolean initDdl;

    /**
     * 全参构造。
     *
     * @param mapper  MyBatis Mapper
     * @param tableName 命令表名（仅字母数字下划线）
     * @param initDdl true 时构造期自动建表
     * @throws IllegalArgumentException 表名为 null 或含白名单外字符
     */
    public MyBatisAsyncCommandStore(AsyncCommandMapper mapper, String tableName, boolean initDdl) {
        if (tableName == null || !SAFE_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("tableName 仅允许字母数字下划线: " + tableName);
        }
        this.mapper = mapper;
        this.table = tableName;
        this.initDdl = initDdl;
        if (initDdl) {
            mapper.createTable(table);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void insert(AsyncCommand cmd) {
        mapper.insert(table, cmd, cmd.getStatusEnum() == null
                ? CommandStatus.PENDING.getCode() : cmd.getStatusEnum().getCode());
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

    /** {@inheritDoc} */
    @Override
    public int reclaimStale(int staleAfterSeconds, int limit) {
        return mapper.reclaimStale(table, java.time.LocalDateTime.now().minusSeconds(staleAfterSeconds), limit);
    }

    /** {@inheritDoc} */
    @Override
    public int updateState(AsyncCommand cmd) {
        return mapper.updateState(table, cmd, cmd.getStatusEnum().getCode());
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
