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

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管控端点（默认关闭：starsite.async-command.admin-enabled=true 且 Web 应用才装配）。
 * 安全注意：无内置鉴权——请确保仅内网/网关白名单可达（路径默认 /api/admin/async/**，
 * 网关不路由 internal 前缀的约定由消费方自持）。
 *
 * @author muyi
 * @date 2026/10/25
 */
@RestController
@RequestMapping("${starsite.async-command.admin-base-path:/api/admin/async}")
public class AsyncCommandAdminController {

    /** 管控服务（查询与处置的本体实现，端点方法全部委托给它） */
    private final AsyncCommandAdminService admin;

    /**
     * 构造管控端点 Controller。
     *
     * @param admin 管控服务（负责实际的查询与处置逻辑）
     */
    public AsyncCommandAdminController(AsyncCommandAdminService admin) {
        this.admin = admin;
    }

    /**
     * 总览端点（GET {基路径}/stats）：各状态计数 + 到期积压快照。
     * <p>安全注意：无内置鉴权，须保证仅内网/网关白名单可达。
     *
     * @return 总览快照（pending/running/success/dead/backlogDue 五项计数）
     */
    @GetMapping("/stats")
    public AsyncCommandAdminService.Stats stats() {
        return admin.stats();
    }

    /**
     * 近 N 条端点（GET {基路径}/recent）：按 id 倒序取最近命令。
     * <p>安全注意：无内置鉴权，返回体含命令载荷（payload），须保证仅内网/网关白名单可达。
     *
     * @param limit 返回条数上限（默认 20）
     * @return 最近的命令列表（id 倒序；无命令时为空列表）
     */
    @GetMapping("/recent")
    public List<com.xp.starsite.asynccmd.AsyncCommand> recent(
            @RequestParam(defaultValue = "20") int limit) {
        return admin.recent(limit);
    }

    /**
     * 分页查询端点（GET {基路径}/list）：按状态/命令类型可选过滤。
     * <p>安全注意：无内置鉴权，须保证仅内网/网关白名单可达。
     *
     * @param status 状态过滤（PENDING/RUNNING/SUCCESS/DEAD；不传则不过滤）
     * @param cmdType 命令类型过滤（执行器 type；不传则不过滤）
     * @param page 页码（从 1 起，默认 1）
     * @param size 每页条数（默认 20）
     * @return Map 含 total（过滤后总条数）与 rows（当前页命令列表）
     */
    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) String cmdType,
                                    @RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "20") int size) {
        return Map.of(
                "total", admin.countPage(status, cmdType),
                "rows", admin.page(status, cmdType, page, size));
    }

    /**
     * 处置端点（POST {基路径}/{id}/retry）：把命令立即打回 PENDING 再跑一轮，可同时清零重试计数。
     * <p>安全注意：写操作且无鉴权，须保证仅内网/网关白名单可达。
     *
     * @param id 命令 id
     * @param resetRetryCount true 时同时把重试计数清零（默认 false，保留已重试次数）
     * @return {"ok": true} 处置成功；{"ok": false} 命令不存在
     */
    @PostMapping("/{id}/retry")
    public Map<String, Object> retry(@PathVariable long id,
                                     @RequestParam(defaultValue = "false") boolean resetRetryCount) {
        return Map.of("ok", admin.retry(id, resetRetryCount));
    }

    /**
     * 处置端点（POST {基路径}/{id}/requeue）：悬挂 RUNNING 打回 PENDING 立即重跑，不等悬挂超时。
     * <p>安全注意：写操作且无鉴权，须保证仅内网/网关白名单可达。
     *
     * @param id 命令 id
     * @return {"ok": true} 处置成功；{"ok": false} 命令不存在
     */
    @PostMapping("/{id}/requeue")
    public Map<String, Object> requeue(@PathVariable long id) {
        return Map.of("ok", admin.requeue(id));
    }

    /**
     * 处置端点（POST {基路径}/{id}/kill）：命令置为 DEAD，不再调度（用于确认无救的命令）。
     * <p>安全注意：写操作且无鉴权，须保证仅内网/网关白名单可达。
     *
     * @param id 命令 id
     * @return {"ok": true} 处置成功；{"ok": false} 命令不存在
     */
    @PostMapping("/{id}/kill")
    public Map<String, Object> kill(@PathVariable long id) {
        return Map.of("ok", admin.kill(id));
    }
}
