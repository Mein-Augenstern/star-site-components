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
package com.xp.starsite.idempotent.api;

/**
 * 幂等门面（业务方编程式入口；注解是声明式入口，两者共用同一引擎与键协议）。
 *
 * <p>实现：core 的 IdempotentEngine（认领/等待/回放/回收四态循环）。
 * 方法名用 guard 不用 execute——安全门禁对 {@code execute(String,...)} 签名
 * 一律按裸 SQL 调用误报（TransactionTemplate 同款教训，component-builder 第 6 步）。
 *
 * @author muyi
 * @date 2026/11/12
 */
public interface IdempotentClient {

    /**
     * 幂等守护一个动作：同键恰一执行 + 窗口内结果回放。
     *
     * <p>语义（与 {@link StarsiteIdempotent @StarsiteIdempotent} 注解一致）：
     * <ul>
     *   <li>键空闲 → 认领执行（EXECUTED）；动作抛异常 → 标记 FAILED 后重抛（键释放）</li>
     *   <li>同键 DONE 未过期 → 回放首次载荷（REPLAYED，动作零执行）</li>
     *   <li>同键在飞 → 等待窗口内轮询（他方完成→回放；他方失败→FAILED 摘要不重跑；
     *       窗口耗尽→CONFLICT）</li>
     *   <li>同键 FAILED / 过期 DONE / 崩溃遗留 PROCESSING → 回收再执行</li>
     * </ul>
     *
     * @param scope  键作用域（隔离不同业务的同名键；单一键空间传空串）
     * @param key    幂等键（非空；建议带业务前缀——orderId/请求 Idempotency-Key 头）
     * @param action 业务动作（返回字符串载荷）
     * @return 结局（四态见 {@link IdempotentResult.Kind}）
     * @throws IllegalArgumentException scope/key 为空或 key 超长
     * @throws RuntimeException 动作失败时原样重抛（RuntimeException/Error）或
     *                           包装 IllegalStateException（受检异常）
     */
    IdempotentResult guard(String scope, String key, IdempotentAction action);

    /**
     * 清理键记忆窗口外的过期行（retention 看护的低频调用；expire_at 早于当前时刻的
     * 行语义已死——回放/回收判定均不受影响，纯表卫生）。
     *
     * <p>两步法批量删除（先按 expire_at 参数化圈定批次主键，再按主键集合参数化删除——
     * 全程参数绑定，分片中间件红线合规）。
     *
     * @param batchSize 单轮批量上限（满批建议调用方追赶轮询）
     * @return 本轮清理行数
     */
    long purgeExpired(int batchSize);
}
