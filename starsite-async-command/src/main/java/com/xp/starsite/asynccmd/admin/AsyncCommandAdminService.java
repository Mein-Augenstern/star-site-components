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

import com.xp.starsite.asynccmd.AsyncCommand;
import com.xp.starsite.asynccmd.store.AsyncCommandStore;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管控服务：积压/死信可视化与人工处置（重试/重占/终止）。纯 core 无 Web 依赖，
 * HTTP 端点由 starter 的 Controller 暴露。
 * <p>
 * 线程安全性：自身无状态，可并发调用；安全性依赖底层 store 实现。
 * 注意：人工处置与调度器的认领存在竞态（处置时命令可能恰被认领），终态以最后一次回写为准。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class AsyncCommandAdminService {

    /** 命令存储：查询与人工处置都经由它落地 */
    private final AsyncCommandStore store;

    /**
     * 构造管控服务。
     *
     * @param store 命令存储
     */
    public AsyncCommandAdminService(AsyncCommandStore store) {
        this.store = store;
    }

    /**
     * 总览快照：四状态计数 + 到期积压（管控页面首屏）。
     *
     * @return 各状态计数与到期积压（积压以本机时钟为基准）
     */
    public Stats stats() {
        return new Stats(
                store.countByStatus(AsyncCommand.PENDING),
                store.countByStatus(AsyncCommand.RUNNING),
                store.countByStatus(AsyncCommand.SUCCESS),
                store.countByStatus(AsyncCommand.DEAD),
                store.countBacklogDue(LocalDateTime.now()));
    }

    /**
     * 最近命令（管控页面首屏用）。
     *
     * @param limit 返回条数上限（条）
     * @return 命令列表（id 倒序，最新在前）
     */
    public List<AsyncCommand> recent(int limit) {
        return store.selectRecent(limit);
    }

    /**
     * 分页查询（页码 → offset 换算在此完成，存储层只认偏移）。
     *
     * @param status  状态过滤（null 或空白 = 不过滤）
     * @param cmdType 命令类型过滤（null 或空白 = 不过滤）
     * @param page    页码（1 起；小于 1 按 1 处理）
     * @param size    页大小（条）
     * @return 当前页命令（id 倒序）
     */
    public List<AsyncCommand> page(String status, String cmdType, long page, int size) {
        long offset = Math.max(0, page - 1) * size;
        return store.selectPage(status, cmdType, offset, size);
    }

    /**
     * 分页计数（与 page 同口径过滤，供页码计算）。
     *
     * @param status  状态过滤（null 或空白 = 不过滤）
     * @param cmdType 命令类型过滤（null 或空白 = 不过滤）
     * @return 满足过滤条件的总条数
     */
    public long countPage(String status, String cmdType) {
        return store.countPage(status, cmdType);
    }

    /**
     * 处置-重试：命令回 PENDING、到期时间置当前时间（下一轮立即再跑）；
     * 可选清零重试计数（给死信命令全新的重试额度，DEAD 因此复活）。
     *
     * @param id              命令主键
     * @param resetRetryCount true 表示 retryCount 清零重新计数；false 保留已重试次数
     * @return 命令存在且回写成功返回 true；命令不存在返回 false
     */
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

    /**
     * 处置-重占：悬挂 RUNNING 打回 PENDING 立即重跑，不等悬挂超时（执行器疑似死亡时的人工介入）。
     *
     * @param id 命令主键
     * @return 命令存在且回写成功返回 true；命令不存在返回 false
     */
    public boolean requeue(long id) {
        AsyncCommand cmd = store.findById(id);
        if (cmd == null) return false;
        cmd.setStatus(AsyncCommand.PENDING);
        cmd.setClaimToken(null);
        cmd.setNextRunAt(LocalDateTime.now());
        store.updateState(cmd);
        return true;
    }

    /**
     * 处置-终止：命令置 DEAD，不再被调度（彻底放弃执行的人工决定）。
     *
     * @param id 命令主键
     * @return 命令存在且回写成功返回 true；命令不存在返回 false
     */
    public boolean kill(long id) {
        AsyncCommand cmd = store.findById(id);
        if (cmd == null) return false;
        cmd.setStatus(AsyncCommand.DEAD);
        cmd.setClaimToken(null);
        store.updateState(cmd);
        return true;
    }

    /**
     * 总览快照（各状态计数 + 到期积压）。
     *
     * @param pending    PENDING 命令条数
     * @param running    RUNNING 命令条数
     * @param success    SUCCESS 命令条数
     * @param dead       DEAD 死信条数
     * @param backlogDue 已到期未执行的 PENDING 条数（积压，消费能力是否跟上的核心指标）
     */
    public record Stats(long pending, long running, long success, long dead, long backlogDue) {}
}
