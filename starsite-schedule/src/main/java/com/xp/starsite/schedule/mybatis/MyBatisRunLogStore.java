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
package com.xp.starsite.schedule.mybatis;

import com.xp.starsite.schedule.api.RunLog;
import com.xp.starsite.schedule.api.RunStatus;
import com.xp.starsite.schedule.store.RunLogStore;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * MyBatis 流水存储（可选路径）：语句在 {@code ScheduleRunLogMapper.xml}（同包资源，
 * 消费方 classpath 自带 mybatis 时由 starter 零配置切换——与 async-command 同形）。
 *
 * <p>表名经白名单校验后以 {@code ${table}} 渲染（构造期校验，防注入）。
 *
 * @author muyi
 * @date 2026/11/02
 */
public class MyBatisRunLogStore implements RunLogStore {

    /** Mapper（SqlSessionFactory 装配） */
    private final ScheduleRunLogMapper mapper;

    /** 表名（白名单校验） */
    private final String table;

    /** id 生成器（sequence 组件） */
    private final LongSupplier idGenerator;

    /**
     * 便捷构造（构造期建表）。
     *
     * @param mapper      Mapper
     * @param table       表名
     * @param idGenerator id 生成器
     */
    public MyBatisRunLogStore(ScheduleRunLogMapper mapper, String table, LongSupplier idGenerator) {
        this(mapper, table, true, idGenerator);
    }

    /**
     * 全参构造。
     *
     * @param mapper      Mapper
     * @param table       表名（[A-Za-z0-9_] 白名单，非法即抛——防 ${table} 渲染注入）
     * @param initDdl     true 构造期建表（幂等）；false 交由迁移脚本管理
     * @param idGenerator id 生成器（sequence 组件）
     */
    public MyBatisRunLogStore(ScheduleRunLogMapper mapper, String table, boolean initDdl,
                              LongSupplier idGenerator) {
        if (table == null || !table.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("表名非法（白名单 [A-Za-z0-9_]）: " + table);
        }
        this.mapper = mapper;
        this.table = table;
        this.idGenerator = idGenerator;
        if (initDdl) {
            mapper.createTable(table);
        }
    }

    /** {@inheritDoc} */
    @Override
    public RunLog start(String taskName, String lockName, LocalDateTime triggerTime,
                        LocalDateTime startedAt, String host) {
        RunLog row = new RunLog(idGenerator.getAsLong(), taskName, lockName, triggerTime,
                startedAt, null, host, RunStatus.RUNNING, null, null);
        mapper.insert(table, row);
        return row;
    }

    /** {@inheritDoc} */
    @Override
    public RunLog skip(String taskName, String lockName, LocalDateTime triggerTime,
                       LocalDateTime at, String host) {
        RunLog row = new RunLog(idGenerator.getAsLong(), taskName, lockName, triggerTime,
                at, at, host, RunStatus.SKIPPED_LOCKED, 0L, null);
        mapper.insert(table, row);
        return row;
    }

    /** {@inheritDoc} */
    @Override
    public void finish(long id, RunStatus status, Long durationMs, String error) {
        mapper.finish(table, id, status.name(), durationMs, error);
    }

    /** {@inheritDoc} */
    @Override
    public int markStaleRunning(LocalDateTime olderThan) {
        return mapper.markStaleRunning(table, olderThan);
    }

    /** {@inheritDoc} */
    @Override
    public List<RunLog> recentByTask(String taskName, int limit) {
        return mapper.recentByTask(table, taskName, Math.max(0, limit));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<RunLog> findById(long id) {
        return Optional.ofNullable(mapper.findById(table, id));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<LocalDateTime> lastDoneTriggerTime(String taskName) {
        return Optional.ofNullable(mapper.lastDoneTriggerTime(table, taskName));
    }
}
