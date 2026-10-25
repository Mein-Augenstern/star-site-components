/**
 * 异步命令核心库（按职责分包）：
 * <ul>
 *   <li>{@code model} 命令模型；{@code config} 运行参数；{@code clock} 时间源抽象</li>
 *   <li>{@code client} 提交入口（含 IMMEDIATE 提交钩子）；
 *       {@code executor} 执行器 SPI（{@code ExecutionMode} 三模式 / 批量 / 池隔离）与注册表</li>
 *   <li>{@code scheduler} 调度器（认领 + 三模式分发 + 组件共享池）；
 *       {@code store} 双存储（jdbc / memory）</li>
 *   <li>{@code metrics} 指标接口；{@code admin} 管控服务</li>
 * </ul>
 * 仅依赖 spring-jdbc（表存储用），可独立于 starter 使用；装配见 starter 模块。
 *
 * @author muyi
 * @date 2026/10/25
 */
package com.xp.starsite.asynccmd;
