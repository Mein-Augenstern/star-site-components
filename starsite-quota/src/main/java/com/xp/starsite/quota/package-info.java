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
 * 余额型配额组件（六号组件，core 零 Spring Boot 依赖）——防超额的通用内核：
 * 额度/库存/席位/保证金/令牌池同一抽象。
 *
 * <p>一条恒不变式：{@code occupied + frozen <= total}——五原子操作
 * （占用/释放/冻结/解冻/冻结转占用）全部是"条件判定+计数更新"的单条原子语句
 * （表形态=条件 UPDATE，Redis=单段 Lua，memory=compute 原子块），
 * 多实例并发下超量必拒（防超贷/防超卖的同一正确性模型）。
 *
 * <p><b>领域边界</b>：组件背正确性（不变式+原子操作），业务背含义——额度树/
 * 冻结原因是业务字段（用多个配额键组合表达）；防"重放"不在本组件职责内
 * （occupy 可重复执行，操作幂等由 idempotent 组件守——正交互补组合拳）。
 *
 * @author muyi
 * @date 2026/11/14
 */
package com.xp.starsite.quota;
