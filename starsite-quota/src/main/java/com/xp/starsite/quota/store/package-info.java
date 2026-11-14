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
 * 配额存储抽象（契约层，开闭原则的"闭"）：本包只放契约——
 * {@link com.xp.starsite.quota.store.QuotaStore} SPI 与 {@code QuotaSql} 方言接口；
 * 契约一旦稳定即对修改关闭（引擎只依赖本包类型）。
 *
 * <p>实现是开放的（"开"）：作为本包的<b>子包</b>存在，一个存储载体一个包——
 * {@code store.jdbc}（表形态，方言可注入）、{@code store.memory}（单机）、
 * {@code store.redis}（Lua 条件更新）、{@code store.mybatis}（消费方 MyBatis 生态）。
 * 新增存储 = 新增一个 store.&lt;载体&gt; 子包并注册 Bean，既有代码零改动
 * （策略模式：引擎面向 SPI 编程，形态经 Bean 让位装配）。扩展指南见 docs/extension.md。
 *
 * @author muyi
 * @date 2026/11/14
 */
package com.xp.starsite.quota.store;
