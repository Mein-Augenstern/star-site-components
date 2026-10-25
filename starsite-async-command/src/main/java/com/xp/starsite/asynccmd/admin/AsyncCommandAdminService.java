package com.xp.starsite.asynccmd.admin;

import com.xp.starsite.asynccmd.AsyncCommand;
import com.xp.starsite.asynccmd.store.AsyncCommandStore;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管控服务：积压/死信可视化与人工处置（重试/重占/终止）。纯 core 无 Web 依赖，
 * HTTP 端点由 starter 的 Controller 暴露。
 *
 * @author muyi
 * @since 2026-10
 */
public class AsyncCommandAdminService {

    private final AsyncCommandStore store;

    public AsyncCommandAdminService(AsyncCommandStore store) {
        this.store = store;
    }

    /** 总览：各状态计数 + 到期积压 */
    public Stats stats() {
        return new Stats(
                store.countByStatus(AsyncCommand.PENDING),
                store.countByStatus(AsyncCommand.RUNNING),
                store.countByStatus(AsyncCommand.SUCCESS),
                store.countByStatus(AsyncCommand.DEAD),
                store.countBacklogDue(LocalDateTime.now()));
    }

    /** 近 N 条（id 倒序） */
    public List<AsyncCommand> recent(int limit) {
        return store.selectRecent(limit);
    }

    /** 分页查询（status/cmdType 可选过滤） */
    public List<AsyncCommand> page(String status, String cmdType, long page, int size) {
        long offset = Math.max(0, page - 1) * size;
        return store.selectPage(status, cmdType, offset, size);
    }

    public long countPage(String status, String cmdType) {
        return store.countPage(status, cmdType);
    }

    /** 处置：重试（立即再跑一轮，可清错误计数） */
    public boolean retry(long id, boolean resetRetryCount) {
        AsyncCommand cmd = store.findById(id);
        if (cmd == null) return false;
        cmd.setStatus(AsyncCommand.PENDING);
        cmd.setClaimToken(null);
        cmd.setNextRunAt(LocalDateTime.now());
        if (resetRetryCount) cmd.setRetryCount(0);
        store.updateState(cmd);
        return true;
    }

    /** 处置：重占（悬挂 RUNNING 打回 PENDING，不等悬挂超时） */
    public boolean requeue(long id) {
        AsyncCommand cmd = store.findById(id);
        if (cmd == null) return false;
        cmd.setStatus(AsyncCommand.PENDING);
        cmd.setClaimToken(null);
        cmd.setNextRunAt(LocalDateTime.now());
        store.updateState(cmd);
        return true;
    }

    /** 处置：终止（进 DEAD，不再调度） */
    public boolean kill(long id) {
        AsyncCommand cmd = store.findById(id);
        if (cmd == null) return false;
        cmd.setStatus(AsyncCommand.DEAD);
        cmd.setClaimToken(null);
        store.updateState(cmd);
        return true;
    }

    /** 总览快照 */
    public record Stats(long pending, long running, long success, long dead, long backlogDue) {}
}
