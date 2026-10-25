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
package com.xp.starsite.asynccmd.store;

import com.xp.starsite.asynccmd.AsyncCommand;
import com.xp.starsite.asynccmd.TimeSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 内存存储（单机无 DB 形态）：语义与 jdbc 实现完全对齐（令牌认领/悬挂回收/退避重试）。
 * 适用：本地开发、小型工具、单机服务；重启丢命令——可靠性链路请用 jdbc 形态。
 * 线程安全：单锁串行（命令量级小，锁无竞争压力）。
 *
 * @author muyi
 * @since 2026-10
 */
public class InMemoryAsyncCommandStore implements AsyncCommandStore {

    private final Map<Long, AsyncCommand> rows = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(0);
    private final TimeSource time;

    public InMemoryAsyncCommandStore() {
        this(TimeSource.system());
    }

    public InMemoryAsyncCommandStore(TimeSource time) {
        this.time = time;
    }

    @Override
    public synchronized void insert(AsyncCommand cmd) {
        cmd.setId(idSeq.incrementAndGet());
        LocalDateTime now = time.now();
        if (cmd.getCreatedAt() == null) cmd.setCreatedAt(now);
        cmd.setUpdatedAt(now);
        rows.put(cmd.getId(), copy(cmd));
    }

    @Override
    public synchronized AsyncCommand findById(long id) {
        AsyncCommand row = rows.get(id);
        return row == null ? null : copy(row);
    }

    @Override
    public synchronized long countUnfinished(String cmdType, String bizKey) {
        return rows.values().stream()
                .filter(c -> cmdType.equals(c.getCmdType()) && bizKey.equals(c.getBizKey()))
                .filter(c -> AsyncCommand.PENDING.equals(c.getStatus()) || AsyncCommand.RUNNING.equals(c.getStatus()))
                .count();
    }

    @Override
    public synchronized int claim(String token, int limit, LocalDateTime now) {
        List<Long> due = rows.values().stream()
                .filter(c -> AsyncCommand.PENDING.equals(c.getStatus()))
                .filter(c -> c.getNextRunAt() == null || !c.getNextRunAt().isAfter(now))
                .sorted(Comparator.comparingLong(AsyncCommand::getId))
                .limit(limit)
                .map(AsyncCommand::getId)
                .collect(Collectors.toList());
        for (Long id : due) {
            AsyncCommand c = rows.get(id);
            c.setStatus(AsyncCommand.RUNNING);
            c.setClaimToken(token);
            c.setUpdatedAt(now);
        }
        return due.size();
    }

    @Override
    public synchronized List<AsyncCommand> findByToken(String token) {
        return rows.values().stream()
                .filter(c -> token.equals(c.getClaimToken()))
                .sorted(Comparator.comparingLong(AsyncCommand::getId))
                .map(this::copy)
                .collect(Collectors.toList());
    }

    @Override
    public synchronized int reclaimStale(int staleAfterSeconds, int limit) {
        LocalDateTime threshold = time.now().minusSeconds(staleAfterSeconds);
        int n = 0;
        for (AsyncCommand c : rows.values()) {
            if (n >= limit) break;
            if (AsyncCommand.RUNNING.equals(c.getStatus()) && c.getUpdatedAt() != null
                    && c.getUpdatedAt().isBefore(threshold)) {
                c.setStatus(AsyncCommand.PENDING);
                c.setClaimToken(null);
                c.setUpdatedAt(time.now());
                n++;
            }
        }
        return n;
    }

    @Override
    public synchronized int updateState(AsyncCommand cmd) {
        AsyncCommand row = rows.get(cmd.getId());
        if (row == null) return 0;
        row.setStatus(cmd.getStatus());
        row.setClaimToken(cmd.getClaimToken());
        row.setRetryCount(cmd.getRetryCount());
        row.setNextRunAt(cmd.getNextRunAt());
        row.setLastError(cmd.getLastError());
        row.setUpdatedAt(time.now());
        return 1;
    }

    @Override
    public synchronized long countByStatus(String status) {
        return rows.values().stream().filter(c -> status.equals(c.getStatus())).count();
    }

    @Override
    public synchronized long countBacklogDue(LocalDateTime now) {
        return rows.values().stream()
                .filter(c -> AsyncCommand.PENDING.equals(c.getStatus()))
                .filter(c -> c.getNextRunAt() == null || !c.getNextRunAt().isAfter(now))
                .count();
    }

    @Override
    public synchronized List<AsyncCommand> selectRecent(int limit) {
        return rows.values().stream()
                .sorted(Comparator.comparingLong(AsyncCommand::getId).reversed())
                .limit(limit)
                .map(this::copy)
                .collect(Collectors.toList());
    }

    @Override
    public synchronized long countPage(String status, String cmdType) {
        return filter(status, cmdType).size();
    }

    @Override
    public synchronized List<AsyncCommand> selectPage(String status, String cmdType, long offset, int size) {
        return filter(status, cmdType).stream()
                .sorted(Comparator.comparingLong(AsyncCommand::getId).reversed())
                .skip(offset)
                .limit(size)
                .map(this::copy)
                .collect(Collectors.toList());
    }

    private List<AsyncCommand> filter(String status, String cmdType) {
        List<AsyncCommand> out = new ArrayList<>(rows.values());
        if (status != null && !status.isBlank()) {
            out = out.stream().filter(c -> status.equals(c.getStatus())).collect(Collectors.toList());
        }
        if (cmdType != null && !cmdType.isBlank()) {
            out = out.stream().filter(c -> cmdType.equals(c.getCmdType())).collect(Collectors.toList());
        }
        return out;
    }

    private AsyncCommand copy(AsyncCommand src) {
        AsyncCommand c = new AsyncCommand();
        c.setId(src.getId());
        c.setCmdType(src.getCmdType());
        c.setBizKey(src.getBizKey());
        c.setPayload(src.getPayload());
        c.setStatus(src.getStatus());
        c.setMaxRetry(src.getMaxRetry());
        c.setRetryCount(src.getRetryCount());
        c.setNextRunAt(src.getNextRunAt());
        c.setLastError(src.getLastError());
        c.setOwner(src.getOwner());
        c.setClaimToken(src.getClaimToken());
        c.setCreatedAt(src.getCreatedAt());
        c.setUpdatedAt(src.getUpdatedAt());
        return c;
    }
}
