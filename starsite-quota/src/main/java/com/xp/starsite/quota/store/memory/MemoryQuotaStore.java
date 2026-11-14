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
package com.xp.starsite.quota.store.memory;

import com.xp.starsite.quota.api.QuotaBalance;
import com.xp.starsite.quota.store.QuotaStore;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内存配额存储（单机/测试形态：进程内 ConcurrentHashMap，重启丢账面——
 * 与家族 memory 形态同取舍说明）。
 *
 * <p>与表形态语义等价（对齐测试互证）：五原子操作各在 {@code compute} 原子块内
 * 完成"条件判定 + 计数更新"——单键串行化，无读改写窗口，不变式同样恒成立。
 *
 * @author muyi
 * @date 2026/11/14
 */
public class MemoryQuotaStore implements QuotaStore {

    /** 可变账面（整行替换保持不可变快照语义；synchronized(ref) 与 compute 互补由 compute 单通道保证） */
    private static final class Account {
        /** 总量 */
        volatile long total;
        /** 已占用 */
        volatile long occupied;
        /** 冻结中 */
        volatile long frozen;

        /**
         * 构造。
         *
         * @param total 总量
         */
        Account(long total) {
            this.total = total;
        }
    }

    /** 账户表（quota_key → 可变账面引用） */
    private final Map<String, AtomicReference<Account>> accounts = new ConcurrentHashMap<>();

    /** 测试发号（生产 memory 形态无表无 id 需求——保留注入位对齐家族构造形态） */
    private final AtomicLong ids = new AtomicLong();

    /**
     * 无参构造。
     */
    public MemoryQuotaStore() {
        // 账户键即业务键——memory 形态无需发号与时间源
    }

    /** {@inheritDoc} compute 原子：不存在建户，存在保留现量 */
    @Override
    public QuotaBalance init(String quotaKey, long total) {
        AtomicReference<Account> created = new AtomicReference<>(new Account(total));
        AtomicReference<Account> actual = accounts.compute(quotaKey, (k, existing) ->
                existing == null ? created : existing);
        Account account = actual.get();
        return new QuotaBalance(quotaKey, account.total, account.occupied, account.frozen);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<QuotaBalance> find(String quotaKey) {
        AtomicReference<Account> ref = accounts.get(quotaKey);
        if (ref == null) {
            return Optional.empty();
        }
        Account a = ref.get();
        return Optional.of(new QuotaBalance(quotaKey, a.total, a.occupied, a.frozen));
    }

    /** {@inheritDoc} 占用：compute 内条件判定+更新（不变式与表形态 WHERE 同义） */
    @Override
    public Optional<QuotaBalance> occupy(String quotaKey, long amount) {
        return mutate(quotaKey, a -> {
            if (a.occupied + a.frozen + amount > a.total) {
                return false;
            }
            a.occupied += amount;
            return true;
        });
    }

    /** {@inheritDoc} */
    @Override
    public Optional<QuotaBalance> release(String quotaKey, long amount) {
        return mutate(quotaKey, a -> {
            if (a.occupied < amount) {
                return false;
            }
            a.occupied -= amount;
            return true;
        });
    }

    /** {@inheritDoc} */
    @Override
    public Optional<QuotaBalance> freeze(String quotaKey, long amount) {
        return mutate(quotaKey, a -> {
            if (a.occupied + a.frozen + amount > a.total) {
                return false;
            }
            a.frozen += amount;
            return true;
        });
    }

    /** {@inheritDoc} */
    @Override
    public Optional<QuotaBalance> unfreeze(String quotaKey, long amount) {
        return mutate(quotaKey, a -> {
            if (a.frozen < amount) {
                return false;
            }
            a.frozen -= amount;
            return true;
        });
    }

    /** {@inheritDoc} 换位在 compute 内原子完成（无中间窗口） */
    @Override
    public Optional<QuotaBalance> confirm(String quotaKey, long amount) {
        return mutate(quotaKey, a -> {
            if (a.frozen < amount) {
                return false;
            }
            a.frozen -= amount;
            a.occupied += amount;
            return true;
        });
    }

    /** {@inheritDoc} 降额保护与表形态 WHERE 同义 */
    @Override
    public Optional<QuotaBalance> adjustTotal(String quotaKey, long delta) {
        return mutate(quotaKey, a -> {
            if (a.total + delta < a.occupied + a.frozen) {
                return false;
            }
            a.total += delta;
            return true;
        });
    }

    /** {@inheritDoc} */
    @Override
    public long countAccounts() {
        return accounts.size();
    }

    /**
     * 原子变更：compute 对同键串行——条件判定与计数更新同一原子块（表形态单条
     * 条件 UPDATE 的等价物）。
     *
     * @param quotaKey 配额键
     * @param op       条件变更操作（返回 false = 条件不中，账面不动）
     * @return 变更后账面；条件不中或账户不存在为 empty
     */
    private Optional<QuotaBalance> mutate(String quotaKey, java.util.function.Predicate<Account> op) {
        AtomicReference<Account> ref = accounts.get(quotaKey);
        if (ref == null) {
            return Optional.empty();
        }
        boolean[] ok = {false};
        accounts.compute(quotaKey, (k, existing) -> {
            if (existing != null && op.test(existing.get())) {
                ok[0] = true;
            }
            return existing;
        });
        if (!ok[0]) {
            return Optional.empty();
        }
        Account a = ref.get();
        return Optional.of(new QuotaBalance(quotaKey, a.total, a.occupied, a.frozen));
    }
}
