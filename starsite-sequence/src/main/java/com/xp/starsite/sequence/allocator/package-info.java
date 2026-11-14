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
 * 机器位分配存储抽象（契约层，开闭原则的"闭"）：本包只放契约——WorkerIdAllocator SPI 与 SequenceSql 方言接口；契约一旦稳定即对修改关闭（调度器/引擎只依赖本包类型）。
 *
 * <p>实现是开放的（"开"）：作为本包的<b>子包</b>存在，一个存储载体一个包——{@code allocator.mysql}（MySQL 原子租约）、{@code allocator.redis}（INCR 取号）、{@code allocator.standalone}（固定值）、{@code allocator.mybatis}（消费方 MyBatis 生态）。新增存储 = 新增一个 allocator.&lt;载体&gt; 子包并注册 Bean，既有代码零改动（策略模式：调度器面向 SPI 编程，形态经 Bean 让位装配）。扩展指南见 docs/extension.md。
 *
 * @author muyi
 * @date 2026/11/14
 */
package com.xp.starsite.sequence.allocator;
