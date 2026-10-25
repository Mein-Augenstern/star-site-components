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
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管控端点（默认关闭：starsite.async-command.admin-enabled=true 且 Web 应用才装配）。
 * <p>
 * 鉴权：每个端点执行前先过 {@link AsyncCommandAdminGuard#check(HttpServletRequest)}——
 * 优先用应用自定义守卫 Bean；否则配置 starsite.async-command.admin-token 启用内置
 * {@link StaticTokenAdminGuard}（校验请求头 X-Starsite-Admin-Token）；两者皆无则放行
 * 并在装配时打 WARN（仅适合本地开发）。守卫抛异常 = 请求拒绝（异常消息作为响应体）。
 * <p>
 * 纵深防御：即便配置了守卫，也建议保持仅内网/网关白名单可达（路径默认 /api/admin/async/**）。
 *
 * @author muyi
 * @date 2026/10/25
 */
@RestController
@RequestMapping("${starsite.async-command.admin-base-path:/api/admin/async}")
public class AsyncCommandAdminController {

    /** 管控服务（查询与处置的本体实现，端点方法全部委托给它） */
    private final AsyncCommandAdminService admin;

    /** 鉴权守卫的惰性提供者：应用自定义 Bean → 内置静态 Token 守卫 → ALLOW_ALL 兜底 */
    private final ObjectProvider<AsyncCommandAdminGuard> guardProvider;

    /**
     * 构造管控端点 Controller。
     *
     * @param admin         管控服务（负责实际的查询与处置逻辑）
     * @param guardProvider 鉴权守卫提供者（缺省时使用 ALLOW_ALL 放行——装配层已告警）
     */
    public AsyncCommandAdminController(AsyncCommandAdminService admin,
                                       ObjectProvider<AsyncCommandAdminGuard> guardProvider) {
        this.admin = admin;
        this.guardProvider = guardProvider;
    }

    /**
     * 请求级鉴权：取守卫执行检查，守卫异常原样上抛（由 Spring 转错误响应）。
     *
     * @param request 当前 HTTP 请求
     */
    private void guard(HttpServletRequest request) throws Exception {
        AsyncCommandAdminGuard g = guardProvider.getIfAvailable();
        (g == null ? AsyncCommandAdminGuard.ALLOW_ALL : g).check(request);
    }

    /**
     * 总览端点（GET {基路径}/stats）：各状态计数 + 到期积压快照。
     *
     * @param request 当前请求（先经鉴权守卫）
     * @return 总览快照（pending/running/success/dead/backlogDue 五项计数）
     * @throws Exception 守卫拒绝时上抛（401/403）
     */
    @GetMapping("/stats")
    public AsyncCommandAdminService.Stats stats(HttpServletRequest request) throws Exception {
        guard(request);
        return admin.stats();
    }

    /**
     * 近 N 条端点（GET {基路径}/recent）：按 id 倒序取最近命令。
     * <p>安全注意：返回体含命令载荷（payload）。
     *
     * @param request 当前请求（先经鉴权守卫）
     * @param limit   返回条数上限（默认 20）
     * @return 最近的命令列表（id 倒序；无命令时为空列表）
     * @throws Exception 守卫拒绝时上抛（401/403）
     */
    @GetMapping("/recent")
    public List<com.xp.starsite.asynccmd.AsyncCommand> recent(
            HttpServletRequest request,
            @RequestParam(defaultValue = "20") int limit) throws Exception {
        guard(request);
        return admin.recent(limit);
    }

    /**
     * 分页查询端点（GET {基路径}/list）：按状态/命令类型可选过滤。
     *
     * @param request 当前请求（先经鉴权守卫）
     * @param status  状态过滤（PENDING/RUNNING/SUCCESS/DEAD；不传则不过滤）
     * @param cmdType 命令类型过滤（执行器 type；不传则不过滤）
     * @param page    页码（从 1 起，默认 1）
     * @param size    每页条数（默认 20）
     * @return Map 含 total（过滤后总条数）与 rows（当前页命令列表）
     * @throws Exception 守卫拒绝时上抛（401/403）
     */
    @GetMapping("/list")
    public Map<String, Object> list(HttpServletRequest request,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String cmdType,
                                    @RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "20") int size) throws Exception {
        guard(request);
        return Map.of(
                "total", admin.countPage(status, cmdType),
                "rows", admin.page(status, cmdType, page, size));
    }

    /**
     * 处置端点（POST {基路径}/{id}/retry）：把命令立即打回 PENDING 再跑一轮，可同时清零重试计数。
     *
     * @param request         当前请求（先经鉴权守卫）
     * @param id              命令 id
     * @param resetRetryCount true 时同时把重试计数清零（默认 false，保留已重试次数）
     * @return {"ok": true} 处置成功；{"ok": false} 命令不存在
     * @throws Exception 守卫拒绝时上抛（401/403）
     */
    @PostMapping("/{id}/retry")
    public Map<String, Object> retry(HttpServletRequest request,
                                     @PathVariable long id,
                                     @RequestParam(defaultValue = "false") boolean resetRetryCount) throws Exception {
        guard(request);
        return Map.of("ok", admin.retry(id, resetRetryCount));
    }

    /**
     * 处置端点（POST {基路径}/{id}/requeue）：悬挂 RUNNING 打回 PENDING 立即重跑，不等悬挂超时。
     *
     * @param request 当前请求（先经鉴权守卫）
     * @param id      命令 id
     * @return {"ok": true} 处置成功；{"ok": false} 命令不存在
     * @throws Exception 守卫拒绝时上抛（401/403）
     */
    @PostMapping("/{id}/requeue")
    public Map<String, Object> requeue(HttpServletRequest request, @PathVariable long id) throws Exception {
        guard(request);
        return Map.of("ok", admin.requeue(id));
    }

    /**
     * 处置端点（POST {基路径}/{id}/kill）：强制置 DEAD 终止调度（如确认业务已无需执行）。
     *
     * @param request 当前请求（先经鉴权守卫）
     * @param id      命令 id
     * @return {"ok": true} 处置成功；{"ok": false} 命令不存在
     * @throws Exception 守卫拒绝时上抛（401/403）
     */
    @PostMapping("/{id}/kill")
    public Map<String, Object> kill(HttpServletRequest request, @PathVariable long id) throws Exception {
        guard(request);
        return Map.of("ok", admin.kill(id));
    }
}
