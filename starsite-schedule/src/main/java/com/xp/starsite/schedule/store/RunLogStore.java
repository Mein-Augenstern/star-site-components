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
package com.xp.starsite.schedule.store;

import com.xp.starsite.schedule.api.RunLog;
import com.xp.starsite.schedule.api.RunStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 执行流水存储（观测层）：schedule_run_log 表的读写接口。
 *
 * <p>实现三形态（策略模式，与三组件同构）：{@link MemoryRunLogStore}（单机观测/测试）/
 * {@link JdbcRunLogStore}（spring-jdbc）/ MyBatisRunLogStore（消费方 classpath 自带
 * mybatis 时零配置切换）。
 *
 * <p>列表/详情分列铁律（D6）：{@link #recentByTask} 不含 error 大列，
 * {@link #findById} 全列。
 *
 * @author muyi
 * @date 2026/11/02
 */
public interface RunLogStore {

    /**
     * 开一条 RUNNING 流水（执行开始）。
     *
     * @param taskName    任务名
     * @param lockName    锁名
     * @param triggerTime 计划触发时刻
     * @param startedAt   实际开始时刻
     * @param host        执行实例（hostname:pid）
     * @return 已落库的 RUNNING 行（含 id）
     */
    RunLog start(String taskName, String lockName, LocalDateTime triggerTime,
                 LocalDateTime startedAt, String host);

    /**
     * 开一条 SKIPPED_LOCKED 流水（未获锁让位——恰一执行的另一半也要可观测）。
     *
     * @param taskName    任务名
     * @param lockName    锁名
     * @param triggerTime 计划触发时刻
     * @param at          让位时刻
     * @param host        让位实例
     * @return 已落库行（含 id）
     */
    RunLog skip(String taskName, String lockName, LocalDateTime triggerTime,
                LocalDateTime at, String host);

    /**
     * 落终态（DONE/FAILED）。
     *
     * @param id         流水 id
     * @param status     终态
     * @param durationMs 时长毫秒（可 null）
     * @param error      失败摘要（非 FAILED 传 null）
     */
    void finish(long id, RunStatus status, Long durationMs, String error);

    /**
     * 悬挂回收：started_at 早于阈值的 RUNNING 批量标 STALE（启动巡检用）。
     *
     * @param olderThan 阈值时刻
     * @return 标记条数
     */
    int markStaleRunning(LocalDateTime olderThan);

    /**
     * 最近流水（列表查询，不含 error 大列；时间倒序）。
     *
     * @param taskName 任务名
     * @param limit    条数上限
     * @return 流水列表
     */
    List<RunLog> recentByTask(String taskName, int limit);

    /**
     * 流水详情（全列含 error）。
     *
     * @param id 流水 id
     * @return 详情；不存在为 empty
     */
    Optional<RunLog> findById(long id);

    /**
     * 最近一次 DONE 的计划触发时刻（misfire 判定基准）。
     *
     * @param taskName 任务名
     * @return 触发时刻；从未成功为 empty
     */
    Optional<LocalDateTime> lastDoneTriggerTime(String taskName);
}
