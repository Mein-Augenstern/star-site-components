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

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 分布式锁表 MyBatis Mapper（DB 模式的全部 SQL 落在这里——实现类零硬编码 SQL）。
 * <p>
 * 设计约定：{@code ${tableName}} / {@code ${lockName}} 为经白名单校验的标识符
 * （MyBatis ${} 字符串替换——表名/锁名无法参数化是 DDL/主键的天然限制），
 * 业务值全部 {@code #{...}} 参数绑定。
 *
 * @author muyi
 * @date 2026/10/25
 */
@org.apache.ibatis.annotations.Mapper
public interface ScheduleLockMapper {

    /**
     * 幂等建锁表（MySQL 8 语法；init-ddl=true 时由 starter 调用）。
     *
     * @param tableName 锁表名
     * @return 影响行数
     */
    @Update("CREATE TABLE IF NOT EXISTS ${tableName} ("
            + "lock_name VARCHAR(64) NOT NULL PRIMARY KEY, "
            + "locked_by VARCHAR(128), "
            + "locked_until TIMESTAMP(3), "
            + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)")
    int createTable(@Param("tableName") String tableName);

    /**
     * 原子争锁：锁行不存在则 INSERT（首次），存在且已过期（或持锁者=自己）则 UPDATE 续期。
     * 单条 UPDATE 的 WHERE 条件保证多实例并发下只有一个命中。
     *
     * @param tableName 锁表名
     * @param lockName  锁名
     * @param ownerId   持锁者标识
     * @param until     持锁到期时刻（NOW + TTL）
     * @return 影响行数（&gt;0 = 获得锁）
     */
    @Update("UPDATE ${tableName} SET locked_by = #{ownerId}, locked_until = #{until} "
            + "WHERE lock_name = #{lockName} "
            + "AND (locked_by IS NULL OR locked_by = #{ownerId} "
            + "OR locked_until IS NULL OR locked_until < #{now})")
    int tryAcquire(@Param("tableName") String tableName,
                   @Param("lockName") String lockName,
                   @Param("ownerId") String ownerId,
                   @Param("until") java.time.LocalDateTime until,
                   @Param("now") java.time.LocalDateTime now);

    /**
     * 首次插入锁行（锁行不存在时的种子——tryAcquire 之前调用，已存在则忽略）。
     *
     * @param tableName 锁表名
     * @param lockName  锁名
     * @return 影响行数
     */
    @Insert("MERGE INTO ${tableName} (lock_name) KEY (lock_name) VALUES (#{lockName})")
    int seedLockRow(@Param("tableName") String tableName, @Param("lockName") String lockName);

    /**
     * 释放锁（仅持锁者有效：locked_by = 自己才清空，他方调用无副作用）。
     *
     * @param tableName 锁表名
     * @param lockName  锁名
     * @param ownerId   持锁者标识
     * @return 影响行数
     */
    @Update("UPDATE ${tableName} SET locked_by = NULL, locked_until = NULL "
            + "WHERE lock_name = #{lockName} AND locked_by = #{ownerId}")
    int release(@Param("tableName") String tableName,
                @Param("lockName") String lockName,
                @Param("ownerId") String ownerId);

    /**
     * 查询锁当前持有者（诊断用）。
     *
     * @param tableName 锁表名
     * @param lockName  锁名
     * @return 持锁者标识；无持锁者或锁不存在返回 null
     */
    @Select("SELECT locked_by FROM ${tableName} WHERE lock_name = #{lockName}")
    String findHolder(@Param("tableName") String tableName, @Param("lockName") String lockName);
}
