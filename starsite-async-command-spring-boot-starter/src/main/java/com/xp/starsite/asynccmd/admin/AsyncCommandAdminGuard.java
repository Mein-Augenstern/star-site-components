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
package com.xp.starsite.asynccmd.admin;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 管控端点鉴权守卫（钩子）：每个管控请求（stats/recent/list/retry/requeue/kill）
 * 在执行前调用 {@link #check(HttpServletRequest)}，抛异常即拒绝（异常由全局异常处理转为 4xx 响应）。
 * <p>
 * 使用方式（三选一，优先级从高到低）：
 * <ol>
 *   <li>应用自定义本接口 Bean（可拿到完整请求上下文：header/principal/来源 IP，接内部鉴权体系）</li>
 *   <li>配置 {@code starsite.async-command.admin-token}：启用内置
 *       {@link StaticTokenAdminGuard}——校验请求头 {@code X-Starsite-Admin-Token} 常量时间比对</li>
 *   <li>都不配：放行所有请求，但应用启动时打 WARN 提醒（仅适合本地开发）</li>
 * </ol>
 *
 * @author muyi
 * @date 2026/10/25
 */
public interface AsyncCommandAdminGuard {

    /** 放行所有请求的空实现（本地开发兜底；生产未配守卫时启动日志会 WARN 提醒） */
    AsyncCommandAdminGuard ALLOW_ALL = request -> { /* 放行 */ };

    /**
     * 请求鉴权检查（管控端点每个方法执行前调用）。
     * 实现约定：检查通过正常返回；不通过抛异常（建议
     * {@link org.springframework.web.server.ResponseStatusException} 携带 401/403），
     * 异常消息会作为错误响应体返回调用方。
     *
     * @param request 当前 HTTP 请求（可取 header/principal/remoteAddr 等）
     * @throws Exception 鉴权不通过时抛出，端点立即终止
     */
    void check(HttpServletRequest request) throws Exception;
}
