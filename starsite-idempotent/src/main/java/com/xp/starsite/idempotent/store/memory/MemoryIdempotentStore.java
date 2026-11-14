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
package com.xp.starsite.idempotent.store.memory;

import com.xp.starsite.idempotent.api.IdempotentRecord;
import com.xp.starsite.idempotent.api.IdempotentStatus;
import com.xp.starsite.idempotent.clock.TimeSource;
import com.xp.starsite.idempotent.store.ClaimOutcome;
import com.xp.starsite.idempotent.store.IdempotentStore;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * 内存幂等存储（单机/测试形态：进程内 ConcurrentHashMap，重启丢记录——
 * 键记忆随进程消亡，与 async-command standalone 队列同取舍说明）。
 *
 * <p>与表形态语义等价（reviewer 第 5 尺，等价测试互证）：
 * 认领互斥 = map.compute 对同键原子；回收互斥 = compute 内条件重置；
 * 终态守卫 = compareAndSet 语义（仅 PROCESSING 接受终态）。
 *
 * @author muyi
 * @date 2026/11/12
 */
public class MemoryIdempotentStore implements IdempotentStore {

    /** 内存键分隔符（不可输入字符——防 scope/key 拼接歧义，仅内部 mapKey 用） */
    private static final char SEP = '\u0001';

    /** 记录表（mapKey → 可变行引用；行本身不可变，转移经整行替换） */
    private final ConcurrentHashMap<String, AtomicReference<IdempotentRecord>> rows =
            new ConcurrentHashMap<>();

    /** 主键 id 生成器（测试用 AtomicLong 递增；生产 memory 形态无 sequence 也可毫秒基线） */
    private final LongSupplier idGenerator;

    /** 时间源（gmt_create/gmt_modified 值来源——表形态为库时钟，这里为注入时钟） */
    private final TimeSource time;

    /**
     * 便捷构造（测试发号器 + 系统时钟）。
     *
     * @param idGenerator id 生成器
     */
    public MemoryIdempotentStore(LongSupplier idGenerator) {
        this(idGenerator, TimeSource.system());
    }

    /**
     * 全参构造。
     *
     * @param idGenerator id 生成器
     * @param time        时间源（活跃信号与创建时刻的值来源；测试注入假时钟推窗口）
     */
    public MemoryIdempotentStore(LongSupplier idGenerator, TimeSource time) {
        this.idGenerator = idGenerator;
        this.time = time;
    }

    /** {@inheritDoc} compute 对同 mapKey 串行化——存在即 duplicate，不存在即插入 PROCESSING */
    @Override
    public ClaimOutcome tryClaim(String scope, String idemKey, LocalDateTime expireAt, String host) {
        String mapKey = mapKey(scope, idemKey);
        IdempotentRecord created = new IdempotentRecord(idGenerator.getAsLong(),
                scope == null ? "" : scope, idemKey, IdempotentStatus.PROCESSING, null, null,
                expireAt, null, host, time.now(), time.now());
        AtomicReference<IdempotentRecord> inserted = new AtomicReference<>(created);
        AtomicReference<IdempotentRecord> actual = rows.compute(mapKey, (k, existing) -> {
            if (existing == null) {
                return inserted;
            }
            return existing;
        });
        // compute 返回既有引用：同引用即本调用插入成功（认领）；否则键已存在
        return actual == inserted ? ClaimOutcome.claimed(created) : ClaimOutcome.duplicate();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<IdempotentRecord> find(String scope, String idemKey) {
        AtomicReference<IdempotentRecord> ref = rows.get(mapKey(scope, idemKey));
        return ref == null ? Optional.empty() : Optional.of(ref.get());
    }

    /** {@inheritDoc} 条件三合一在 compute 内原子判定——与表形态单条 UPDATE 条件同语义 */
    @Override
    public Optional<IdempotentRecord> tryReclaim(String scope, String idemKey,
                                                 LocalDateTime expireAt, LocalDateTime now,
                                                 LocalDateTime staleBefore, String host) {
        String mapKey = mapKey(scope, idemKey);
        AtomicReference<IdempotentRecord> ref = rows.get(mapKey);
        if (ref == null) {
            return Optional.empty();
        }
        synchronized (ref) {
            IdempotentRecord cur = ref.get();
            if (!reclaimable(cur, now, staleBefore)) {
                return Optional.empty();
            }
            IdempotentRecord next = new IdempotentRecord(cur.getId(), cur.getScope(),
                    cur.getIdemKey(), IdempotentStatus.PROCESSING, null, null, expireAt, null,
                    host, cur.getGmtCreate(), time.now());
            ref.set(next);
            return Optional.of(next);
        }
    }

    /** {@inheritDoc} 双条件守卫（PROCESSING + 本代 expire_at）——代际令牌，与表形态同语义 */
    @Override
    public boolean complete(String scope, String idemKey, LocalDateTime claimExpireAt, String result) {
        return transition(scope, idemKey, claimExpireAt, IdempotentStatus.DONE, result, null);
    }

    /** {@inheritDoc} */
    @Override
    public boolean fail(String scope, String idemKey, LocalDateTime claimExpireAt, String error) {
        return transition(scope, idemKey, claimExpireAt, IdempotentStatus.FAILED, null, error);
    }

    /** {@inheritDoc} */
    @Override
    public long countByStatus(IdempotentStatus status) {
        return rows.values().stream().map(AtomicReference::get)
                .filter(r -> r.getStatus() == status).count();
    }

    /** {@inheritDoc} 条件移除（compute 返回 null 即删——与表形态过期删除同判定） */
    @Override
    public int purgeExpired(int batchSize, LocalDateTime now) {
        int[] removed = {0};
        rows.forEach((mapKey, ref) -> {
            if (removed[0] >= batchSize) {
                return;
            }
            IdempotentRecord r = ref.get();
            if (r.getExpireAt() != null && r.getExpireAt().isBefore(now)) {
                rows.compute(mapKey, (k, existing) -> {
                    if (existing != null && existing.get().getExpireAt() != null
                            && existing.get().getExpireAt().isBefore(now)) {
                        removed[0]++;
                        return null;
                    }
                    return existing;
                });
            }
        });
        return removed[0];
    }

    /**
     * 终态转移（双条件守卫：PROCESSING 且 expire_at=本代认领值——按 mapKey 定位，
     * 与表形态 WHERE scope=? AND idem_key=? AND status='PROCESSING' AND expire_at=? 同形）。
     *
     * @param scope        键作用域
     * @param idemKey      幂等键
     * @param claimExpireAt 本代认领写入的 expire_at
     * @param status       终态
     * @param result       成功载荷
     * @param error        失败摘要
     * @return true 写入成功；false 行不存在/非 PROCESSING/已换代（被回收）
     */
    private boolean transition(String scope, String idemKey, LocalDateTime claimExpireAt,
                               IdempotentStatus status, String result, String error) {
        AtomicReference<IdempotentRecord> ref = rows.get(mapKey(scope, idemKey));
        if (ref == null) {
            return false;
        }
        synchronized (ref) {
            IdempotentRecord cur = ref.get();
            if (cur == null || cur.getStatus() != IdempotentStatus.PROCESSING
                    || !sameGeneration(cur.getExpireAt(), claimExpireAt)) {
                return false;
            }
            ref.set(new IdempotentRecord(cur.getId(), cur.getScope(), cur.getIdemKey(),
                    status, result, error, cur.getExpireAt(), time.now(), cur.getHost(),
                    cur.getGmtCreate(), time.now()));
            return true;
        }
    }

    /**
     * 代际令牌等值（显式精度契约）：两侧统一截断到秒再比较——表形态 DATETIME 秒精度
     * / memory 测试假时钟可含纳秒，精度不一致时 equals 会静默换代失败（DATETIME 截断
     * bug 的 memory 版暗雷）。截断后 equals 在同代恒真、跨代恒假，契约进代码不进注释。
     *
     * @param stored       行内持的窗口边界（本代认领写入值）
     * @param claimExpireAt 调用方透传的代际令牌
     * @return true 同代
     */
    private static boolean sameGeneration(LocalDateTime stored, LocalDateTime claimExpireAt) {
        if (stored == null || claimExpireAt == null) {
            return false;
        }
        return stored.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .equals(claimExpireAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    /**
     * 回收条件（与方言 reclaim 语句的三合一条件逐条对齐）。
     *
     * @param cur         当前行
     * @param now         当前时刻
     * @param staleBefore PROCESSING 停滞阈值时刻
     * @return true 可回收
     */
    private static boolean reclaimable(IdempotentRecord cur, LocalDateTime now,
                                       LocalDateTime staleBefore) {
        if (cur.getStatus() == IdempotentStatus.FAILED) {
            return true;
        }
        if (cur.getExpireAt() != null && cur.getExpireAt().isBefore(now)) {
            return true;
        }
        return cur.getStatus() == IdempotentStatus.PROCESSING
                && cur.getGmtModified() != null && cur.getGmtModified().isBefore(staleBefore);
    }

    /**
     * mapKey 拼接（scope + 分隔符 + key；分隔符为不可输入字符防拼接歧义）。
     *
     * @param scope   作用域（null 归一空串——与表形态 DEFAULT '' 对齐）
     * @param idemKey 幂等键
     * @return map 键
     */
    private static String mapKey(String scope, String idemKey) {
        return (scope == null ? "" : scope) + SEP + idemKey;
    }
}
