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
package com.xp.starsite.quota.store;

import com.xp.starsite.quota.api.QuotaBalance;
import java.util.Optional;

/**
 * 配额存储 SPI（四内置实现同语义等价——jdbc/mybatis 表形态、redis Lua 形态、
 * memory 单机形态；四载体的条件片段由对齐测试互证）。
 *
 * <p><b>第三方存储扩展点</b>：实现本接口并注册为 Spring Bean（starter 全量让位）
 * 即可接入自建存储。<b>唯一硬契约：五原子操作必须存储端原子</b>——
 * 占用/释放/冻结/解冻/转销各是"条件判定 + 计数更新"的一条原子语句
 * （表形态=单条条件 UPDATE，Redis=单段 Lua），这是不变式
 * {@code occupied + frozen <= total} 在多实例并发下成立的<b>全部</b>正确性来源。
 * "先读账面再写"的两步实现一律不合格（读改写竞态会击穿不变式）。
 *
 * <p>返回值语义：操作成功返回操作后的余额快照；条件不满足或账户不存在统一
 * {@code Optional.empty()}——由引擎区分异常种类（引擎会再查一次账面定责）。
 *
 * @author muyi
 * @date 2026/11/14
 */
public interface QuotaStore {

    /**
     * 建户（幂等）：键不存在则建（total 落库）；已存在<b>不改</b>现有总量，
     * 返回当前账面（重复初始化安全——提降额走 {@link #adjustTotal}）。
     *
     * @param quotaKey 配额键
     * @param total    总量（建户用；已存在时忽略）
     * @return 建户后或已存在的余额
     */
    QuotaBalance init(String quotaKey, long total);

    /**
     * 查账。
     *
     * @param quotaKey 配额键
     * @return 余额；账户不存在为 empty
     */
    Optional<QuotaBalance> find(String quotaKey);

    /**
     * 占用（原子）：条件 {@code occupied + frozen + amount <= total} →
     * {@code occupied += amount}；条件不中或账户不存在为 empty。
     *
     * @param quotaKey 配额键
     * @param amount   占用量（正数，引擎已校验）
     * @return 占用后余额；条件不满足为 empty
     */
    Optional<QuotaBalance> occupy(String quotaKey, long amount);

    /**
     * 释放占用（原子）：条件 {@code occupied >= amount} → {@code occupied -= amount}。
     *
     * @param quotaKey 配额键
     * @param amount   释放量（正数）
     * @return 释放后余额；占用不足或账户不存在为 empty
     */
    Optional<QuotaBalance> release(String quotaKey, long amount);

    /**
     * 冻结（原子）：条件同 {@link #occupy}（共享总量池）→ {@code frozen += amount}。
     *
     * @param quotaKey 配额键
     * @param amount   冻结量（正数）
     * @return 冻结后余额；条件不满足为 empty
     */
    Optional<QuotaBalance> freeze(String quotaKey, long amount);

    /**
     * 解冻（原子）：条件 {@code frozen >= amount} → {@code frozen -= amount}。
     *
     * @param quotaKey 配额键
     * @param amount   解冻量（正数）
     * @return 解冻后余额；冻结不足或账户不存在为 empty
     */
    Optional<QuotaBalance> unfreeze(String quotaKey, long amount);

    /**
     * 冻结转占用（原子换位）：条件 {@code frozen >= amount} →
     * {@code frozen -= amount, occupied += amount}（单条语句同时改两计数——
     * 换位期间不变式天然保持，无窗口）。
     *
     * @param quotaKey 配额键
     * @param amount   转销量（正数）
     * @return 转销后余额；冻结不足或账户不存在为 empty
     */
    Optional<QuotaBalance> confirm(String quotaKey, long amount);

    /**
     * 调整总量（原子）：条件（仅降额方向）{@code total + delta >= occupied + frozen}
     * → {@code total += delta}；提额方向恒满足。
     *
     * @param quotaKey 配额键
     * @param delta    增量（正提额/负降额）
     * @return 调整后余额；降额越界或账户不存在为 empty
     */
    Optional<QuotaBalance> adjustTotal(String quotaKey, long delta);

    /**
     * 账户计数（健康端点用——低频）。
     * <p><b>观测降级缺省</b>：不支持全库计数的形态（redis——SCAN 代价与健康端点
     * 不匹配）保持缺省 -1，健康端点对负值省略微报。
     *
     * @return 账户数；不支持计数为 -1
     */
    default long countAccounts() {
        return -1;
    }
}
