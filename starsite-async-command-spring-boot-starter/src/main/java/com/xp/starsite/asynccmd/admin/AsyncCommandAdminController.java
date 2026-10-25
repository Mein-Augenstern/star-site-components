package com.xp.starsite.asynccmd.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管控端点（默认关闭：starsite.async-command.admin-enabled=true 且 Web 应用才装配）。
 * 安全注意：无内置鉴权——请确保仅内网/网关白名单可达（路径默认 /api/admin/async/**，
 * 网关不路由 internal 前缀的约定由消费方自持）。
 *
 * @author muyi
 * @since 2026-10
 */
@RestController
@RequestMapping("${starsite.async-command.admin-base-path:/api/admin/async}")
public class AsyncCommandAdminController {

    private final AsyncCommandAdminService admin;

    public AsyncCommandAdminController(AsyncCommandAdminService admin) {
        this.admin = admin;
    }

    /** 总览：各状态计数 + 到期积压 */
    @GetMapping("/stats")
    public AsyncCommandAdminService.Stats stats() {
        return admin.stats();
    }

    /** 近 N 条 */
    @GetMapping("/recent")
    public List<com.xp.starsite.asynccmd.AsyncCommand> recent(
            @RequestParam(defaultValue = "20") int limit) {
        return admin.recent(limit);
    }

    /** 分页 */
    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) String cmdType,
                                    @RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "20") int size) {
        return Map.of(
                "total", admin.countPage(status, cmdType),
                "rows", admin.page(status, cmdType, page, size));
    }

    /** 处置：重试 */
    @PostMapping("/{id}/retry")
    public Map<String, Object> retry(@PathVariable long id,
                                     @RequestParam(defaultValue = "false") boolean resetRetryCount) {
        return Map.of("ok", admin.retry(id, resetRetryCount));
    }

    /** 处置：重占（悬挂打回） */
    @PostMapping("/{id}/requeue")
    public Map<String, Object> requeue(@PathVariable long id) {
        return Map.of("ok", admin.requeue(id));
    }

    /** 处置：终止 */
    @PostMapping("/{id}/kill")
    public Map<String, Object> kill(@PathVariable long id) {
        return Map.of("ok", admin.kill(id));
    }
}
