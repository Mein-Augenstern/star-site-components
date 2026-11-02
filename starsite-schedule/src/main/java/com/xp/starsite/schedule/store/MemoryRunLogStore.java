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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 内存流水存储：单机观测/测试形态（无 DB 场景的降级路径）。
 *
 * <p>终态字段 volatile 可变——finish 更新内存行；recentByTask/findById 返回当时快照
 * （终态视图由查询时刻决定）。上限 {@value #MAX_ROWS} 行 FIFO 淘汰（观测窗口足够，
 * 无界增长才是 bug）。id 为进程内发号——memory 形态本就单机，跨实例唯一性不在承诺内。
 *
 * @author muyi
 * @date 2026/11/02
 */
public class MemoryRunLogStore implements RunLogStore {

    /** 内存保留上限（FIFO 淘汰） */
    public static final int MAX_ROWS = 1000;

    /** 内存行（终态字段可变——finish 时更新） */
    private static class Row {
        final long id;
        final String taskName;
        final String lockName;
        final LocalDateTime triggerTime;
        final LocalDateTime startedAt;
        final String host;
        volatile RunStatus status;
        volatile LocalDateTime finishedAt;
        volatile Long durationMs;
        volatile String error;

        Row(RunLog log) {
            this.id = log.getId();
            this.taskName = log.getTaskName();
            this.lockName = log.getLockName();
            this.triggerTime = log.getTriggerTime();
            this.startedAt = log.getStartedAt();
            this.host = log.getHost();
            this.status = log.getStatus();
            this.finishedAt = log.getFinishedAt();
            this.durationMs = log.getDurationMs();
            this.error = log.getError();
        }

        RunLog snapshot() {
            return new RunLog(id, taskName, lockName, triggerTime, startedAt, finishedAt, host,
                    status, durationMs, error);
        }
    }

    /** 行表（插入序；锁保护——引擎线程写、任意线程读） */
    private final List<Row> rows = new ArrayList<>();

    /** 串行化锁（读写都短临界区） */
    private final ReentrantLock lock = new ReentrantLock();

    /** 内存 id 发号器（毫秒×1000 起步递增——单机形态够用） */
    private final AtomicLong idGen = new AtomicLong(System.currentTimeMillis() * 1000L);

    /** {@inheritDoc} */
    @Override
    public RunLog start(String taskName, String lockName, LocalDateTime triggerTime,
                        LocalDateTime startedAt, String host) {
        return insert(new RunLog(idGen.incrementAndGet(), taskName, lockName, triggerTime,
                startedAt, null, host, RunStatus.RUNNING, null, null));
    }

    /** {@inheritDoc} */
    @Override
    public RunLog skip(String taskName, String lockName, LocalDateTime triggerTime,
                       LocalDateTime at, String host) {
        return insert(new RunLog(idGen.incrementAndGet(), taskName, lockName, triggerTime,
                at, at, host, RunStatus.SKIPPED_LOCKED, 0L, null));
    }

    /** {@inheritDoc} */
    @Override
    public void finish(long id, RunStatus status, Long durationMs, String error) {
        lock.lock();
        try {
            findRow(id).ifPresent(row -> {
                row.status = status;
                row.finishedAt = LocalDateTime.now();
                row.durationMs = durationMs;
                row.error = error;
            });
        } finally {
            lock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public int markStaleRunning(LocalDateTime olderThan) {
        int marked = 0;
        lock.lock();
        try {
            for (Row row : rows) {
                if (row.status == RunStatus.RUNNING && row.startedAt.isBefore(olderThan)) {
                    row.status = RunStatus.STALE;
                    row.finishedAt = LocalDateTime.now();
                    marked++;
                }
            }
        } finally {
            lock.unlock();
        }
        return marked;
    }

    /** {@inheritDoc} 列表不带 error（分列铁律 D6） */
    @Override
    public List<RunLog> recentByTask(String taskName, int limit) {
        List<RunLog> out = new ArrayList<>();
        lock.lock();
        try {
            for (int i = rows.size() - 1; i >= 0 && out.size() < Math.max(0, limit); i--) {
                Row row = rows.get(i);
                if (row.taskName.equals(taskName)) {
                    out.add(new RunLog(row.id, row.taskName, row.lockName, row.triggerTime,
                            row.startedAt, row.finishedAt, row.host, row.status, row.durationMs));
                }
            }
        } finally {
            lock.unlock();
        }
        return out;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<RunLog> findById(long id) {
        lock.lock();
        try {
            return findRow(id).map(Row::snapshot);
        } finally {
            lock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<LocalDateTime> lastDoneTriggerTime(String taskName) {
        lock.lock();
        try {
            for (int i = rows.size() - 1; i >= 0; i--) {
                Row row = rows.get(i);
                if (row.taskName.equals(taskName) && row.status == RunStatus.DONE) {
                    return Optional.of(row.triggerTime);
                }
            }
        } finally {
            lock.unlock();
        }
        return Optional.empty();
    }

    /** 插入（FIFO 淘汰到上限） */
    private RunLog insert(RunLog log) {
        Row row = new Row(log);
        lock.lock();
        try {
            rows.add(row);
            if (rows.size() > MAX_ROWS) {
                rows.subList(0, rows.size() - MAX_ROWS).clear();
            }
        } finally {
            lock.unlock();
        }
        return log;
    }

    /** 按 id 找内存行（调用方持锁） */
    private Optional<Row> findRow(long id) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (rows.get(i).id == id) {
                return Optional.of(rows.get(i));
            }
        }
        return Optional.empty();
    }
}
