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
package com.xp.starsite.quota.api;

/**
 * 配额门面（业务方唯一入口；六号组件 quota——余额型资源账户的通用内核）。
 *
 * <p>一个配额账户 = 一个键（{@code credit-limit:product-A} / {@code inventory:sku-1} /
 * {@code seat:enterprise-1}……业务自定）+ 总量与两个计数器。组件承诺一条恒不变式：
 * <b>{@code occupied + frozen <= total}</b>——五原子操作全部经单条条件 UPDATE / Lua
 * 保证，多实例并发下超量操作必被拒绝（防超贷/防超卖的同一正确性模型）。
 *
 * <p>金额单位由业务自定（分/件/个），组件只约束非负 long；额度树/冻结原因等业务
 * 语义是业务字段（业务侧用多个账户键组合表达），组件不管业务含义。
 *
 * <p><b>防重组合拳</b>：本组件防"超额"不防"重放"——occupy 天然可重复执行（重试会
 * 占用两次），操作幂等由 idempotent 组件守（同一业务单号恰一执行），两者正交互补。
 *
 * <p>典型用法（信贷支用防超贷）：
 * <pre>{@code
 * quotaClient.init("credit:product-A", 1_000_000_00L);       // 授信 100 万（分）
 * quotaClient.occupy("credit:product-A", 50_000_00L);        // 放款占用 50 万
 * quotaClient.release("credit:product-A", 50_000_00L);       // 放款失败冲正释放
 * }</pre>
 *
 * @author muyi
 * @date 2026/11/14
 */
public interface QuotaClient {

    /**
     * 初始化配额账户（幂等：键已存在时不改现有总量，返回当前账户余额——重复部署/
     * 重复初始化安全）。
     *
     * @param quotaKey    配额键（全局唯一，业务自定——带业务前缀防撞）
     * @param totalAmount 总量（非负；单位业务自定）
     * @return 初始化后（或已存在的）账户余额
     * @throws IllegalArgumentException quotaKey 为空/超长，或 totalAmount 为负
     */
    QuotaBalance init(String quotaKey, long totalAmount);

    /**
     * 查询账户余额。
     *
     * @param quotaKey 配额键
     * @return 余额；账户不存在为 empty
     */
    java.util.Optional<QuotaBalance> balance(String quotaKey);

    /**
     * 占用（确定性扣减：放款/出货/消耗）。
     * <p>条件：{@code occupied + frozen + amount <= total}，单条原子 UPDATE——
     * 并发下超量必拒，不变式恒成立。
     *
     * @param quotaKey 配额键
     * @param amount   占用量（正数）
     * @return 占用后的余额
     * @throws QuotaNotFoundException  账户不存在（先 init）
     * @throws QuotaExceededException  可用量不足（异常携带当前可用量——业务可展示"还可支用多少"）
     * @throws IllegalArgumentException amount 非正
     */
    QuotaBalance occupy(String quotaKey, long amount);

    /**
     * 释放占用（占用回退：放款失败冲正/退货回补）。
     *
     * @param quotaKey 配额键
     * @param amount   释放量（正数，≤当前占用）
     * @return 释放后的余额
     * @throws QuotaNotFoundException   账户不存在
     * @throws QuotaStateException      释放量大于当前占用（业务侧记账错乱的信号——防御性拒绝）
     * @throws IllegalArgumentException amount 非正
     */
    QuotaBalance release(String quotaKey, long amount);

    /**
     * 冻结（预占：下单先冻结、确认时转占用；支付在途冻结）。
     * <p>条件同 {@link #occupy}（占用+冻结共享总量池）。
     *
     * @param quotaKey 配额键
     * @param amount   冻结量（正数）
     * @return 冻结后的余额
     * @throws QuotaNotFoundException 账户不存在
     * @throws QuotaExceededException 可用量不足
     * @throws IllegalArgumentException amount 非正
     */
    QuotaBalance freeze(String quotaKey, long amount);

    /**
     * 解冻（冻结回退：订单取消，预占释放回可用池）。
     *
     * @param quotaKey 配额键
     * @param amount   解冻量（正数，≤当前冻结）
     * @return 解冻后的余额
     * @throws QuotaNotFoundException 账户不存在
     * @throws QuotaStateException    解冻量大于当前冻结
     * @throws IllegalArgumentException amount 非正
     */
    QuotaBalance unfreeze(String quotaKey, long amount);

    /**
     * 冻结转占用（预占确认：下单成功，冻结量原子划转为占用——总量池内换位，
     * 不变量自动保持）。
     *
     * @param quotaKey 配额键
     * @param amount   转销量（正数，≤当前冻结）
     * @return 转销后的余额
     * @throws QuotaNotFoundException 账户不存在
     * @throws QuotaStateException    转销量大于当前冻结
     * @throws IllegalArgumentException amount 非正
     */
    QuotaBalance confirm(String quotaKey, long amount);

    /**
     * 调整总量（提额/降额/补货）。
     * <p>降额保护：新总量不足以覆盖 {@code occupied + frozen} 时拒绝
     * （不允许调出负可用量的状态）。
     *
     * @param quotaKey 配额键
     * @param delta    增量（正=提额，负=降额）
     * @return 调整后的余额
     * @throws QuotaNotFoundException 账户不存在
     * @throws QuotaStateException    降额后不足以覆盖占用+冻结
     */
    QuotaBalance adjustTotal(String quotaKey, long delta);
}
