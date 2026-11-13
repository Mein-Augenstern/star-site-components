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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明幂等方法：同一键的重复请求只执行一次，首次成功结果在键记忆窗口内直接回放
 * （Stripe idempotency key 同款语义）。
 *
 * <p>示例（下单防重复提交——网络重试/用户双击/网关重发的统一解）：
 * <pre>{@code
 * @PostMapping("/orders")
 * @StarsiteIdempotent(key = "#request.orderId")          // SpEL 取参数
 * public OrderResult create(@RequestBody CreateRequest request) { ... }
 *
 * @StarsiteIdempotent(key = "${report.key}", scope = "daily-report")  // 字面量 + ${} 占位符
 * public Report export(String day) { ... }
 * }</pre>
 *
 * <p><b>语义契约</b>（与门面 {@link IdempotentClient} 完全一致）：
 * <ul>
 *   <li>首个请求认领键并执行；窗口内的重复请求回放首次成功结果，业务方法零执行</li>
 *   <li>执行抛异常 → 键释放（FAILED），后续同键请求<b>重新执行</b>（失败可重试是幂等键
 *       与缓存的关键差异）</li>
 *   <li>在飞期间（他人 PROCESSING）重复请求等待 {@code wait-millis} 窗口，超时抛
 *       {@link IdempotentConflictException}（web 侧建议映射 409）</li>
 *   <li>认领者崩溃 → PROCESSING 停滞超 processing-stale 阈值后，下一个请求回收再执行（自愈）</li>
 * </ul>
 *
 * <p>结果回放经 Jackson 序列化（starter 的注解路径；门面路径业务自管载荷字符串）——
 * 返回值须可 JSON 往返（DTO 常态；{@code void} 方法合法，回放即空返回）。
 *
 * @author muyi
 * @date 2026/11/12
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface StarsiteIdempotent {

    /**
     * 幂等键表达式。两种形态（starter 的 BPP 解析）：
     * <ul>
     *   <li>含 {@code #}：按 SpEL 对方法参数求值（如 {@code #orderId}、
     *       {@code 'export-' + #day}）——参数名经字节码调试信息解析</li>
     *   <li>不含 {@code #}：字面量（先解析 {@code ${...}} 占位符——键进配置中心/yml 集中管理）</li>
     * </ul>
     * 求值结果为 null/空串 → 调用期快速失败（幂等键缺失即语义不成立，不静默放行）。
     *
     * @return 键表达式（SpEL 或字面量）
     */
    String key();

    /**
     * 键作用域（隔离同名键的不同业务操作——同一 orderId 在"下单"与"支付"是两个键）。
     * <p>缺省：声明类简单名#方法名（与 schedule 组件任务名缺省同惯例）；
     * 支持 {@code ${...}} 占位符。
     *
     * @return 作用域；空串用缺省值
     */
    String scope() default "";
}
