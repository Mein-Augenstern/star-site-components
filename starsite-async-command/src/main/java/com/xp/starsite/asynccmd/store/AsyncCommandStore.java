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
 * <p>
 * 异常约定：存储访问失败时抛出底层运行时异常（jdbc 实现为 Spring DataAccessException，
 * memory 实现一般不抛），各方法不再逐一声明具体异常类型。
 * <p>
 * 线程安全性：实现必须支持多线程并发调用（jdbc 依赖无状态 JdbcTemplate，memory 以单锁串行）。
 *
 * @author muyi
 * @date 2026/10/25
 */
public interface AsyncCommandStore {

    /**
     * 新建命令：将 cmd 落入存储并分配主键。
     * 实现负责回填 cmd.id（自增/发号）并维护创建/更新时间；
     * status、nextRunAt 等业务字段应已由调用方填好。
     *
     * @param cmd 待落库命令（id 传入值会被实现重新分配覆盖）
     * @throws RuntimeException 存储访问失败时（异常约定见类注释）
     */
    void insert(AsyncCommand cmd);

    /**
     * 按主键查询命令。
     *
     * @param id 命令主键
     * @return 命令；不存在返回 null
     * @throws RuntimeException 存储访问失败时（异常约定见类注释）
     */
    AsyncCommand findById(long id);

    /**
     * 幂等检查：统计同命令类型 + 业务键下未完成（PENDING/RUNNING）的命令数。
     *
     * @param cmdType 命令类型（精确匹配，非 null）
     * @param bizKey  业务键（精确匹配，非 null；提交侧已把 null 归一为空串）
     * @return 未完成命令条数（0 表示同键无在途命令，可提交新命令）
     * @throws RuntimeException 存储访问失败时（异常约定见类注释）
     */
    long countUnfinished(String cmdType, String bizKey);

    /**
     * 乐观认领（两步令牌法第一步）：把到期 PENDING 命令打上令牌并置 RUNNING。
     * 多实例并发认领同一存储时，靠该更新动作的原子性互斥——同一条命令只会被一个令牌认领。
     *
     * @param token 本轮认领令牌（实例内每轮唯一，建议随机 UUID）
     * @param limit 单轮认领上限（条，须为正数）
     * @param now   到期判定基准时间（nextRunAt ≤ now 视为到期；测试可传假时钟时间）
     * @return 实际认领条数（0 表示当前无到期命令）
     * @throws RuntimeException 存储访问失败时（异常约定见类注释）
     */
    int claim(String token, int limit, LocalDateTime now);

    /**
     * 按令牌取回本轮认领的命令（两步令牌法第二步，认领后调用）。
     * 只会命中仍持有该令牌的命令——已回写终态（令牌被清空）的不会返回。
     *
     * @param token 认领时使用的令牌
     * @return 该令牌名下的命令（id 升序）；无命中返回空列表
     * @throws RuntimeException 存储访问失败时（异常约定见类注释）
     */
    List<AsyncCommand> findByToken(String token);

    /**
     * 崩溃回收：RUNNING 超过阈值秒未完成（执行进程死亡的悬挂残留）→ 回 PENDING 并清令牌，等待重新调度。
     * 阈值必须大于单条命令最长执行时长，否则活命令会被误回收导致重复执行。
     *
     * @param staleAfterSeconds 悬挂判定阈值（秒）
     * @param limit             单轮回收上限（条）
     * @return 实际回收条数（0 表示无悬挂）
     * @throws RuntimeException 存储访问失败时（异常约定见类注释）
     */
    int reclaimStale(int staleAfterSeconds, int limit);

    /**
     * 状态回写：按 id 更新 status/claimToken/retryCount/nextRunAt/lastError 五个字段，其余字段不动。
     *
     * @param cmd 携带最新状态字段的命令（id 必填，指向要更新的行）
     * @return 影响行数：1 表示回写成功；0 表示命令不存在（如已被清理）
     * @throws RuntimeException 存储访问失败时（异常约定见类注释）
     */
    int updateState(AsyncCommand cmd);

    // ---- 管控查询 ----

    /**
     * 按状态计数（管控总览用）。
     *
     * @param status 状态（PENDING/RUNNING/SUCCESS/DEAD 四常量之一）
     * @return 该状态命令条数
     * @throws RuntimeException 存储访问失败时（异常约定见类注释）
     */
    long countByStatus(String status);

    /**
     * 积压统计：已到期（nextRunAt ≤ now）但仍未执行的 PENDING 命令数（消费是否跟上的核心指标）。
     *
     * @param now 到期判定基准时间
     * @return 到期待执行条数
     * @throws RuntimeException 存储访问失败时（异常约定见类注释）
     */
    long countBacklogDue(LocalDateTime now);

    /**
     * 最近命令查询（管控页面首屏用）。
     *
     * @param limit 返回条数上限（条）
     * @return 命令列表（id 倒序，最新在前）；不足 limit 条时返回全部
     * @throws RuntimeException 存储访问失败时（异常约定见类注释）
     */
    List<AsyncCommand> selectRecent(int limit);

    /**
     * 分页计数（与 selectPage 同口径过滤，供页码计算）。
     *
     * @param status  状态过滤（null 或空白 = 不过滤）
     * @param cmdType 命令类型过滤（null 或空白 = 不过滤）
     * @return 满足过滤条件的总条数
     * @throws RuntimeException 存储访问失败时（异常约定见类注释）
     */
    long countPage(String status, String cmdType);

    /**
     * 分页查询（与 countPage 同口径过滤）。
     *
     * @param status  状态过滤（null 或空白 = 不过滤）
     * @param cmdType 命令类型过滤（null 或空白 = 不过滤）
     * @param offset  行偏移（0 起；注意是偏移不是页码，页码换算由调用方完成）
     * @param size    页大小（条）
     * @return 当前页命令（id 倒序，最新在前）；无数据返回空列表
     * @throws RuntimeException 存储访问失败时（异常约定见类注释）
     */
    List<AsyncCommand> selectPage(String status, String cmdType, long offset, int size);
}
