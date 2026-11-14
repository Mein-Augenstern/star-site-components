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
package com.xp.starsite.quota.core;

import com.xp.starsite.quota.api.QuotaBalance;
import com.xp.starsite.quota.api.QuotaClient;
import com.xp.starsite.quota.api.QuotaExceededException;
import com.xp.starsite.quota.api.QuotaNotFoundException;
import com.xp.starsite.quota.api.QuotaStateException;
import com.xp.starsite.quota.store.QuotaStore;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 配额引擎（{@link QuotaClient} 门面实现）：存储操作的条件结果翻译为精确异常——
 * 不变量与原子性全部在存储端（SPI 契约），引擎只做参数校验与<b>定责</b>
 * （条件不中时再查一次账面：账户不在=NotFound；账面能判断=Exceeded/State）。
 *
 * <pre>
 * occupy/freeze 不中 ──► 查账：无户→NotFound；available<请求→Exceeded(带可用量)
 * release/unfreeze/confirm 不中 ──► 查账：无户→NotFound；计数不足→State(记账错乱信号)
 * adjustTotal 不中 ──► 查账：无户→NotFound；降额越界→State
 * </pre>
 *
 * @author muyi
 * @date 2026/11/14
 */
public class QuotaEngine implements QuotaClient {

    private static final Logger log = LoggerFactory.getLogger(QuotaEngine.class);

    /** 配额键列宽上限（表 quota_key VARCHAR(128)，超长 fail-fast 防静默截断撞键） */
    private static final int KEY_MAX = 128;

    /** 配额存储（原子性与不变式的唯一事实源——D4 存储端铁律） */
    private final QuotaStore store;

    /**
     * 构造。
     *
     * @param store 配额存储
     */
    public QuotaEngine(QuotaStore store) {
        this.store = store;
    }

    /** {@inheritDoc} */
    @Override
    public QuotaBalance init(String quotaKey, long totalAmount) {
        validateKey(quotaKey);
        if (totalAmount < 0) {
            throw new IllegalArgumentException("总量不可为负: " + totalAmount);
        }
        return store.init(quotaKey.trim(), totalAmount);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<QuotaBalance> balance(String quotaKey) {
        validateKey(quotaKey);
        return store.find(quotaKey.trim());
    }

    /** {@inheritDoc} */
    @Override
    public QuotaBalance occupy(String quotaKey, long amount) {
        validateAmount(amount);
        return apply(quotaKey, amount, store::occupy, "占用");
    }

    /** {@inheritDoc} */
    @Override
    public QuotaBalance release(String quotaKey, long amount) {
        validateAmount(amount);
        return apply(quotaKey, amount, store::release, "释放");
    }

    /** {@inheritDoc} */
    @Override
    public QuotaBalance freeze(String quotaKey, long amount) {
        validateAmount(amount);
        return apply(quotaKey, amount, store::freeze, "冻结");
    }

    /** {@inheritDoc} */
    @Override
    public QuotaBalance unfreeze(String quotaKey, long amount) {
        validateAmount(amount);
        return apply(quotaKey, amount, store::unfreeze, "解冻");
    }

    /** {@inheritDoc} */
    @Override
    public QuotaBalance confirm(String quotaKey, long amount) {
        validateAmount(amount);
        return apply(quotaKey, amount, store::confirm, "冻结转占用");
    }

    /** {@inheritDoc} */
    @Override
    public QuotaBalance adjustTotal(String quotaKey, long delta) {
        validateKey(quotaKey);
        return apply(quotaKey, delta, store::adjustTotal, "调整总量");
    }

    /**
     * 通用执行与定责：存储条件不中（empty）→ 重查账面精确定位异常种类。
     *
     * @param quotaKey 配额键
     * @param operand  操作量（正数或 delta）
     * @param op       存储操作
     * @param opName   操作名（异常消息用）
     * @return 操作后余额
     */
    private QuotaBalance apply(String quotaKey, long operand,
                               java.util.function.BiFunction<String, Long, Optional<QuotaBalance>> op,
                               String opName) {
        String key = quotaKey.trim();
        Optional<QuotaBalance> result = op.apply(key, operand);
        if (result.isPresent()) {
            return result.get();
        }
        // 条件不中：定责（账户不在 vs 额度不足 vs 记账错乱）
        QuotaBalance snapshot = store.find(key).orElseThrow(() -> new QuotaNotFoundException(key));
        switch (opName) {
            case "占用", "冻结" -> throw new QuotaExceededException(key, operand, snapshot.getAvailable());
            case "调整总量" -> throw new QuotaStateException(
                    ("降额越界: key=%s, 现总量=%d, 占用+冻结=%d, 增量=%d——不允许调出负可用量")
                            .formatted(key, snapshot.getTotal(),
                                    snapshot.getOccupied() + snapshot.getFrozen(), operand),
                    key);
            default -> throw new QuotaStateException(
                    ("账面计数不足: key=%s, %s 量=%d, 当前占用=%d, 冻结=%d——业务侧记账错乱的信号"
                            + "（重复释放/漏记占用？），防御性拒绝").formatted(key, opName, operand,
                            snapshot.getOccupied(), snapshot.getFrozen()),
                    key);
        }
    }

    /**
     * 键校验（空/超长 fail-fast——键缺失即账户定位不成立）。
     *
     * @param quotaKey 配额键
     */
    private static void validateKey(String quotaKey) {
        if (quotaKey == null || quotaKey.isBlank()) {
            throw new IllegalArgumentException("配额键不可为空");
        }
        if (quotaKey.trim().length() > KEY_MAX) {
            throw new IllegalArgumentException("配额键超长（" + quotaKey.trim().length()
                    + " > " + KEY_MAX + "，表列宽上限——改短键）: " + quotaKey.trim().substring(0, 16) + "…");
        }
    }

    /**
     * 量校验（正数——零量操作无意义且会污染账面语义）。
     *
     * @param amount 操作量
     */
    private static void validateAmount(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("操作量必须为正数: " + amount);
        }
    }
}
