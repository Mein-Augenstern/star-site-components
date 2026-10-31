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
package com.xp.starsite.lock.mybatis;

import com.xp.starsite.lock.client.LockAdminClient.LockInfo;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;

/**
 * 分布式锁表 MyBatis Mapper（接口与 SQL 分离——SQL 全部在同包
 * {@code ScheduleLockMapper.xml}，由 MyBatis 按命名空间自动发现加载）。
 * <p>
 * 约定：{@code ${tableName}} 为经白名单校验的表名（字符串替换），
 * 业务值全部 {@code #{...}} 参数绑定。主键 id 由 starsite-sequence 组件生成。
 *
 * @author muyi
 * @date 2026/10/25
 */
@org.apache.ibatis.annotations.Mapper
public interface ScheduleLockMapper {

    /**
     * 幂等建锁表（init-ddl=true 时由 starter 调用；生产建议迁移脚本管理）。
     *
     * @param tableName 锁表名
     * @return 影响行数
     */
    int createTable(@Param("tableName") String tableName);

    /**
     * 结构校验（BUG-009）：列出锁表实际列名。表名经 {@code #{}} 参数绑定（非 {@code ${}}
     * 拼接）——元数据查询无注入面；表不存在返回空集（DBA 管理场景由调用方跳过校验）。
     *
     * @param tableName 锁表名
     * @return 实际列名清单（MySQL/H2 的 INFORMATION_SCHEMA 通用）
     */
    java.util.List<String> selectColumns(@Param("tableName") String tableName);

    /**
     * 原子争锁：locked_by 为空 / 已过期 / 是自己（续期）三种情形命中。
     * 单条 UPDATE 原子保证多实例并发下只有一个命中。
     *
     * @param tableName 锁表名
     * @param lockName  锁名
     * @param ownerId   持锁者标识
     * @param until     持锁到期时刻（NOW + TTL）
     * @param now       当前时刻（参数绑定，消 NOW() 方言差异）
     * @return 影响行数（&gt;0 = 获得锁）
     */
    int tryAcquire(@Param("tableName") String tableName,
                   @Param("lockName") String lockName,
                   @Param("ownerId") String ownerId,
                   @Param("until") LocalDateTime until,
                   @Param("now") LocalDateTime now);

    /**
     * 首次插入锁行种子（INSERT ... WHERE NOT EXISTS 只插不覆盖——保住他方持锁状态）。
     *
     * @param tableName 锁表名
     * @param id        锁行主键
     * @param lockName  锁名
     * @return 影响行数
     */
    int seedLockRow(@Param("tableName") String tableName,
                    @Param("id") long id,
                    @Param("lockName") String lockName);

    /**
     * 释放锁（仅持锁者有效：locked_by = 自己才清空）。
     *
     * @param tableName 锁表名
     * @param lockName  锁名
     * @param ownerId   持锁者标识
     * @return 影响行数
     */
    int release(@Param("tableName") String tableName,
                @Param("lockName") String lockName,
                @Param("ownerId") String ownerId);

    /**
     * 查询锁当前持有者（诊断用）。
     *
     * @param tableName 锁表名
     * @param lockName  锁名
     * @return 持锁者标识；无持锁者返回 null
     */
    String findHolder(@Param("tableName") String tableName, @Param("lockName") String lockName);

    /**
     * 强制释放锁（运维侧门：无视持有者与计数清空全部持有字段）。
     *
     * @param tableName 锁表名
     * @param lockName  锁名
     * @return 影响行数（&gt;0 = 行存在且已清空）
     */
    int forceRelease(@Param("tableName") String tableName, @Param("lockName") String lockName);

    /**
     * 列出全部持锁（运维侧门：诊断/巡检）。
     *
     * @param tableName 锁表名
     * @return 持锁信息列表（locked_by 非空的行）
     */
    java.util.List<LockInfo> selectHeldLocks(
            @Param("tableName") String tableName);

    /**
     * 单锁状态快照（运维侧门：含未持有行）。
     *
     * @param tableName 锁表名
     * @param lockName  锁名
     * @return 锁信息；行不存在返回 null
     */
    LockInfo selectLockInfo(
            @Param("tableName") String tableName, @Param("lockName") String lockName);
}
