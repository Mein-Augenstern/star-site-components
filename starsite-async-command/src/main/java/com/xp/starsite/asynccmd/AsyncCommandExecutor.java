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
 * @since 2026-10
 */
public interface AsyncCommandExecutor {

    /** 命令类型路由键 */
    String type();

    /**
     * 执行命令。正常返回 = 成功；抛异常 = 失败触发指数退避重试，超限进 DEAD（死信）。
     */
    void execute(AsyncCommand cmd) throws Exception;
}
