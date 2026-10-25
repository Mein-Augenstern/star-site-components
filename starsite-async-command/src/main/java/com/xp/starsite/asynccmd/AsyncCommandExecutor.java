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
package com.xp.starsite.asynccmd;

/**
 * 异步命令执行器 SPI：业务实现为 Bean（starter 装配形态）或手工注册（纯 core 形态），
 * {@link #type()} 即路由键。同一 cmdType 多个实现会启动报错（路由必须唯一）。
 *
 * @author muyi
 * @date 2026/10/25
 */
public interface AsyncCommandExecutor {

    /**
     * 返回命令类型路由键。
     * 调度器以该值与命令的 cmdType 精确匹配，将命令路由到本执行器。
     *
     * @return 命令类型路由键（全局必须唯一，重复 type 在启动期抛路由冲突异常）
     */
    String type();

    /**
     * 执行一条已认领的命令。
     * 契约：正常返回即视为成功（调度器回写 SUCCESS）；抛出异常即视为失败，
     * 进入指数退避重试，超过 maxRetry 后进 DEAD 死信。
     * 实现要求：失败重试会重复投递同一命令，实现必须保证业务幂等（建议以 bizKey 为去重锚点）。
     *
     * @param cmd 待执行命令（payload 为 JSON 字符串，结构由各执行器自行约定并解析）
     * @throws Exception 业务处理失败——实现不要吞异常，调度器依赖异常触发重试链路
     */
    void execute(AsyncCommand cmd) throws Exception;
}
