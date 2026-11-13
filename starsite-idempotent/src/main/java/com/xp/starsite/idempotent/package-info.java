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

/**
 * API 幂等键组件（core，零 Spring Boot 依赖）。
 *
 * <p>Stripe 同款幂等键语义（docs/idempotent.md）：同一键的重复请求不只被拦截——
 * <b>首次成功的结果直接回放</b>。一层模型三件事：
 * <ul>
 *   <li>认领（store/）：uk(scope, idem_key) INSERT 原子认领，多实例并发恰一执行</li>
 *   <li>执行（core/IdempotentEngine）：认领者运行业务动作，成功落 DONE + result 载荷，
 *       失败落 FAILED（键释放——失败后的新请求重新执行，与 Stripe 5xx 释放键同语义）</li>
 *   <li>回放（core/IdempotentEngine）：DONE 且未过 expire_at 键记忆窗口的重复请求
 *       直接返回存储的 result 载荷，业务动作零执行</li>
 * </ul>
 *
 * <p><b>崩溃自愈契约</b>：认领者崩溃遗留 PROCESSING 行（gmt_modified 停滞）超过
 * processing-stale 阈值后，下一个同键请求条件回收再执行——不依赖 TTL 轮询，
 * 惰性自愈（与 lock 组件"锁行丢失自愈"同哲学）。
 *
 * @author muyi
 * @date 2026/11/12
 */
package com.xp.starsite.idempotent;
