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
package com.xp.starsite.asynccmd.executor;

import com.xp.starsite.asynccmd.model.AsyncCommand;

/**
 * 执行器 SPI：业务实现为 Bean（starter 装配形态）或手工注册（纯 core 形态），
 * {@link #type()} 即路由键。同一 cmdType 多个实现会启动报错（路由必须唯一）。
 * <p>
 * 可选能力（default 方法 + 组合接口，按需覆写/实现）：
 * <ul>
 *   <li>{@link #mode()}——执行模式（默认 {@link ExecutionMode#SCHEDULED} 内联串行）；
 *       时效敏感任务返回 IMMEDIATE，重/慢任务返回 ASYNC_POOL</li>
 *   <li>实现 {@link PoolIsolatedExecutor}——为该类型绑定独立线程池（bean 注入隔离）</li>
 *   <li>实现 {@link AsyncCommandBatchExecutor}——同轮同类型合并批量执行</li>
 * </ul>
 *
 * @author muyi
 * @date 2026/10/25
 */
public interface AsyncCommandExecutor {

    /**
     * 命令类型路由键（全应用唯一，重复注册启动期拒绝）。
     *
     * @return 类型标识（同时作为指标 tag 与幂等键的一部分）
     */
    String type();

    /**
     * 执行模式：决定命令何时跑、跑在哪个线程（见 {@link ExecutionMode} 各模式语义）。
     * 默认 SCHEDULED（调度线程内联串行）；覆写返回 ASYNC_POOL/IMMEDIATE 启用池化。
     *
     * @return 该类型命令的执行模式
     */
    default ExecutionMode mode() {
        return ExecutionMode.SCHEDULED;
    }

    /**
     * 执行命令。正常返回 = 成功；抛异常 = 失败触发指数退避重试，超限进 DEAD（死信）。
     * 实现约束：副作用需幂等（任何模式都可能重试重放）。
     *
     * @param cmd 待执行命令（payload 为业务约定的 JSON）
     * @throws Exception 业务失败时抛出，进入重试链路
     */
    void execute(AsyncCommand cmd) throws Exception;
}
