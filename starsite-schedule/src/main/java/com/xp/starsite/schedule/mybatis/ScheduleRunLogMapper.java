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
package com.xp.starsite.schedule.mybatis;

import com.xp.starsite.schedule.api.RunLog;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 流水表 MyBatis 映射（可选路径：消费方 classpath 自带 mybatis 时由 starter 零配置切换；
 * XML 在同包路径 resources 下——MyBatis 官方约定按接口 FQN 自动加载，D3 决策）。
 *
 * @author muyi
 * @date 2026/11/02
 */
public interface ScheduleRunLogMapper {

    /**
     * 建表（幂等；表名经白名单校验后由 XML ${table} 渲染）。
     *
     * @param table 表名
     */
    void createTable(@Param("table") String table);

    /**
     * 插入流水行。
     *
     * @param table        表名
     * @param row          行数据（id 已预生成）
     * @return 影响行数
     */
    int insert(@Param("table") String table, @Param("row") RunLog row);

    /**
     * 落终态。
     *
     * @param table     表名
     * @param id        流水 id
     * @param status    终态
     * @param durationMs 时长毫秒（可 null）
     * @param error     失败摘要（可 null）
     * @return 影响行数
     */
    int finish(@Param("table") String table, @Param("id") long id,
               @Param("status") String status, @Param("durationMs") Long durationMs,
               @Param("error") String error);

    /**
     * 悬挂回收。
     *
     * @param table     表名
     * @param olderThan 阈值时刻
     * @return 影响行数
     */
    int markStaleRunning(@Param("table") String table, @Param("olderThan") LocalDateTime olderThan);

    /**
     * 最近流水列表（不含 error 大列）。
     *
     * @param table    表名
     * @param taskName 任务名
     * @param limit    条数上限
     * @return 流水列表
     */
    List<RunLog> recentByTask(@Param("table") String table, @Param("taskName") String taskName,
                              @Param("limit") int limit);

    /**
     * 详情（全列含 error）。
     *
     * @param table 表名
     * @param id    流水 id
     * @return 详情；不存在为 null
     */
    RunLog findById(@Param("table") String table, @Param("id") long id);

    /**
     * 最近一次 DONE 的计划触发时刻。
     *
     * @param table    表名
     * @param taskName 任务名
     * @return 触发时刻；从未成功为 null
     */
    LocalDateTime lastDoneTriggerTime(@Param("table") String table, @Param("taskName") String taskName);
}
