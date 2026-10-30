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

import com.xp.starsite.asynccmd.clock.TimeSource;
import com.xp.starsite.asynccmd.model.AsyncCommand;
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
 * <p>
 * 线程安全性：全部公开方法 synchronized 单锁串行（命令量级小，锁无竞争压力）；
 * 对外返回与入库存放一律经 {@link #copy} 快照隔离——调用方拿不到内部行对象，无法绕过锁并发修改。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class InMemoryAsyncCommandStore implements AsyncCommandStore {

    /** 命令行（id → 内部快照）：访问已在锁内串行，ConcurrentHashMap 为防御性兜底（即使未来局部去锁也不会立刻踩坏） */
    private final Map<Long, AsyncCommand> rows = new ConcurrentHashMap<>();
    /** 主键发号器：CAS 自增，insert 时分配 id */
    private final AtomicLong idSeq = new AtomicLong(0);
    /** 时间源：gmtCreate/gmtModified 时间戳与到期判定使用（测试可注入假时钟） */
    private final TimeSource time;

    /**
     * 便捷构造（时间源取系统时钟）。
     */
    public InMemoryAsyncCommandStore() {
        this(TimeSource.system());
    }

    /**
     * 全参构造。
     *
     * @param time 时间源（测试注入假时钟用）
     */
    public InMemoryAsyncCommandStore(TimeSource time) {
        this.time = time;
    }

    /**
     * 新建命令：发号器分配 id 回填；gmtCreate 未设置时取当前时间，gmtModified 刷新；
     * 存放的是入参的快照拷贝，与调用方持有的对象互不影响。
     *
     * @param cmd 待落库命令
     */
    @Override
    public synchronized void insert(AsyncCommand cmd) {
        cmd.setId(idSeq.incrementAndGet());
        LocalDateTime now = time.now();
        if (cmd.getGmtCreate() == null) cmd.setGmtCreate(now);
        cmd.setGmtModified(now);
        rows.put(cmd.getId(), copy(cmd));
    }

    /**
     * 按主键查询命令。
     *
     * @param id 命令主键
     * @return 内部行的快照拷贝；不存在返回 null
     */
    @Override
    public synchronized AsyncCommand findById(long id) {
        AsyncCommand row = rows.get(id);
        return row == null ? null : copy(row);
    }

    /**
     * 幂等检查：统计同 type+bizKey 的未完成（PENDING/RUNNING）命令数（全表线性扫描，量级小可接受）。
     *
     * @param cmdType 命令类型（精确匹配）
     * @param bizKey  业务键（精确匹配）
     * @return 未完成命令条数
     */
    @Override
    public synchronized long countUnfinished(String cmdType, String bizKey) {
        return rows.values().stream()
                .filter(c -> cmdType.equals(c.getCmdType()) && bizKey.equals(c.getBizKey()))
                .filter(c -> AsyncCommand.PENDING.equals(c.getStatus()) || AsyncCommand.RUNNING.equals(c.getStatus()))
                .count();
    }

    /**
     * 乐观认领：按 id 升序选出到期 PENDING（nextRunAt 为 null 视为到期），置 RUNNING 并打令牌。
     * "筛选 + 打令牌"两步在同一把锁内完成，天然原子——并发调用不会认领到同一条命令。
     *
     * @param token 本轮认领令牌
     * @param limit 单轮认领上限（条）
     * @param now   到期判定基准时间
     * @return 实际认领条数
     */
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
            c.setGmtModified(now);
        }
        return due.size();
    }

    /**
     * 按令牌取回本轮认领的命令。
     *
     * @param token 认领时使用的令牌
     * @return 快照列表（id 升序）；无命中返回空列表
     */
    @Override
    public synchronized List<AsyncCommand> findByToken(String token) {
        return rows.values().stream()
                .filter(c -> token.equals(c.getClaimToken()))
                .sorted(Comparator.comparingLong(AsyncCommand::getId))
                .map(this::copy)
                .collect(Collectors.toList());
    }

    /**
     * 崩溃回收：以 gmtModified（认领时刷新）判定悬挂——RUNNING 且 gmtModified 早于
     * now - staleAfterSeconds 的行回 PENDING 并清令牌（与 jdbc 实现以库时钟判定的语义对齐）。
     *
     * @param staleAfterSeconds 悬挂判定阈值（秒）
     * @param limit             单轮回收上限（条）
     * @return 实际回收条数
     */
    @Override
    public synchronized boolean claimById(long id, String token) {
        AsyncCommand c = rows.get(id);
        if (c == null || !AsyncCommand.PENDING.equals(c.getStatus())) {
            return false;
        }
        // 单次取钟：到期判定与认领回写同基准（两次 now() 有毫秒级漂移）
        LocalDateTime now = time.now();
        if (c.getNextRunAt() != null && c.getNextRunAt().isAfter(now)) {
            return false;
        }
        c.setStatus(AsyncCommand.RUNNING);
        c.setClaimToken(token);
        c.setGmtModified(now);
        return true;
    }

    @Override
    public synchronized int reclaimStale(int staleAfterSeconds, int limit) {
        LocalDateTime threshold = time.now().minusSeconds(staleAfterSeconds);
        int n = 0;
        for (AsyncCommand c : rows.values()) {
            if (n >= limit) break;
            if (AsyncCommand.RUNNING.equals(c.getStatus()) && c.getGmtModified() != null
                    && c.getGmtModified().isBefore(threshold)) {
                c.setStatus(AsyncCommand.PENDING);
                c.setClaimToken(null);
                c.setGmtModified(time.now());
                n++;
            }
        }
        return n;
    }

    /**
     * 状态回写：按 id 覆盖五个状态字段并刷新 gmtModified。
     *
     * @param cmd 携带最新状态字段的命令
     * @return 1 回写成功；0 命令不存在
     */
    @Override
    public synchronized int updateState(AsyncCommand cmd) {
        AsyncCommand row = rows.get(cmd.getId());
        if (row == null) return 0;
        row.setStatus(cmd.getStatus());
        row.setClaimToken(cmd.getClaimToken());
        row.setRetryCount(cmd.getRetryCount());
        row.setNextRunAt(cmd.getNextRunAt());
        row.setLastError(cmd.getLastError());
        row.setGmtModified(time.now());
        return 1;
    }

    /**
     * 按状态计数。
     *
     * @param status 状态（四常量之一）
     * @return 该状态命令条数
     */
    @Override
    public synchronized long countByStatus(String status) {
        return rows.values().stream().filter(c -> status.equals(c.getStatus())).count();
    }

    /**
     * 积压统计：已到期未执行的 PENDING 条数（nextRunAt 为 null 视为到期）。
     *
     * @param now 到期判定基准时间
     * @return 到期待执行条数
     */
    @Override
    public synchronized long countBacklogDue(LocalDateTime now) {
        return rows.values().stream()
                .filter(c -> AsyncCommand.PENDING.equals(c.getStatus()))
                .filter(c -> c.getNextRunAt() == null || !c.getNextRunAt().isAfter(now))
                .count();
    }

    /**
     * 最近命令查询。
     *
     * @param limit 返回条数上限（条）
     * @return 快照列表（id 倒序，最新在前）
     */
    @Override
    public synchronized List<AsyncCommand> selectRecent(int limit) {
        return rows.values().stream()
                .sorted(Comparator.comparingLong(AsyncCommand::getId).reversed())
                .limit(limit)
                .map(this::copyAsListItem)   // 列表不携带 payload 大列（与表形态显式列清单等价）
                .collect(Collectors.toList());
    }

    /**
     * 分页计数（与 selectPage 同口径过滤）。
     *
     * @param status  状态过滤（null 或空白 = 不过滤）
     * @param cmdType 命令类型过滤（null 或空白 = 不过滤）
     * @return 满足过滤条件的总条数
     */
    @Override
    public synchronized long countPage(String status, String cmdType) {
        return filter(status, cmdType).size();
    }

    /**
     * 分页查询（与 countPage 同口径过滤）。
     *
     * @param status  状态过滤（null 或空白 = 不过滤）
     * @param cmdType 命令类型过滤（null 或空白 = 不过滤）
     * @param offset  行偏移（0 起，非页码）
     * @param size    页大小（条）
     * @return 当前页快照列表（id 倒序）
     */
    @Override
    public synchronized List<AsyncCommand> selectPage(String status, String cmdType, long offset, int size) {
        return filter(status, cmdType).stream()
                .sorted(Comparator.comparingLong(AsyncCommand::getId).reversed())
                .skip(offset)
                .limit(size)
                .map(this::copyAsListItem)  // 列表不携带 payload 大列（与表形态显式列清单等价）
                .collect(Collectors.toList());
    }

    /**
     * 按可选条件过滤（须在锁内调用：读的是内部行集合）。
     *
     * @param status  状态过滤（null 或空白 = 不过滤）
     * @param cmdType 命令类型过滤（null 或空白 = 不过滤）
     * @return 过滤后的命令列表（内部行对象，未拷贝——仅供本类内部继续加工）
     */
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

    /**
     * 列表项快照：全字段拷贝后 payload 置空——与表形态（selectRecent/selectPage 显式列清单
     * 不含 payload 大列）行为等价；需要载荷的路径走 findById/findByToken。
     *
     * @param src 源命令
     * @return payload 为空的快照
     */
    private AsyncCommand copyAsListItem(AsyncCommand src) {
        AsyncCommand c = copy(src);
        c.setPayload(null);
        return c;
    }

    /**
     * 全字段快照拷贝：入库存放与对外返回都走这里，隔离调用方与内部行，防止绕过锁的并发修改。
     *
     * @param src 源命令
     * @return 与源对象无共享可变引用的新实例
     */
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
        c.setGmtCreate(src.getGmtCreate());
        c.setGmtModified(src.getGmtModified());
        return c;
    }
}
