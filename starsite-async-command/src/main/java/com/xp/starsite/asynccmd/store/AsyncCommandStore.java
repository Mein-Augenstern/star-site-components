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
package com.xp.starsite.asynccmd.store;

import com.xp.starsite.asynccmd.AsyncCommand;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 异步命令存储抽象：jdbc（MySQL 表，单机/分布式通用）与 memory（进程内）双实现。
 * 认领语义（两步令牌法，MySQL 无 UPDATE..RETURNING 的经典解）：
 * 先给到期 PENDING 打上实例唯一 claim_token 置 RUNNING（并发实例互斥，谁写入算谁），
 * 再按令牌取回本轮认领的命令。
 *
 * @author muyi
 * @since 2026-10
 */
public interface AsyncCommandStore {

    /** 新建命令（id 由存储分配并回填） */
    void insert(AsyncCommand cmd);

    /** 按 id 取命令（不存在返回 null） */
    AsyncCommand findById(long id);

    /** 幂等检查：同 type+key 未完成（PENDING/RUNNING）命令数 */
    long countUnfinished(String cmdType, String bizKey);

    /** 乐观认领：到期 PENDING 打令牌置 RUNNING，返回认领条数 */
    int claim(String token, int limit, LocalDateTime now);

    /** 按令牌取回本轮认领的命令 */
    List<AsyncCommand> findByToken(String token);

    /** 崩溃回收：RUNNING 超过 staleAfter 秒未完成 → 回 PENDING 清令牌 */
    int reclaimStale(int staleAfterSeconds, int limit);

    /** 状态回写（status/claimToken/retryCount/nextRunAt/lastError） */
    int updateState(AsyncCommand cmd);

    // ---- 管控查询 ----

    /** 各状态计数 */
    long countByStatus(String status);

    /** 积压：到期未执行的 PENDING 数 */
    long countBacklogDue(LocalDateTime now);

    /** 近 N 条（id 倒序） */
    List<AsyncCommand> selectRecent(int limit);

    /** 分页计数（status/cmdType 可选过滤） */
    long countPage(String status, String cmdType);

    /** 分页查询（status/cmdType 可选过滤，id 倒序） */
    List<AsyncCommand> selectPage(String status, String cmdType, long offset, int size);
}
