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
package com.xp.starsite.sequence.mybatis;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * workerId 租约 MyBatis Mapper（注解式——SQL 即框架原生形态，LAST_INSERT_ID 语义不变）。
 * <p>
 * 核心是 {@link #takeFromGlobalPool} + {@link #selectLastInsertId} 的两步组合：
 * MyBatis 两次调用各自获取新 SqlSession——但 LAST_INSERT_ID 是 MySQL 连接级会话变量，
 * 连接池归还后再取会拿错值。因此本 Mapper 的两步必须在同一事务/连接内执行，
 * {@link MyBatisMysqlWorkerIdAllocator} 通过编程式事务保证（见其注释）。
 * <p>
 * 表名参数 {@code ${table}} 经白名单校验后字符串替换（防注入），业务值一律 {@code #{...}}。
 *
 * @author muyi
 * @date 2026/10/25
 */
@org.apache.ibatis.annotations.Mapper
public interface SequenceMapper {

    /**
     * 播种全局池计数行（INSERT IGNORE 幂等）。
     *
     * @param table       表名
     * @param poolKey     全局池 key（__global__）
     * @param instanceTag 实例标记（'pool'）
     * @return 影响行数
     */
    @Insert("INSERT IGNORE INTO ${table} (service_name, worker_id, instance) "
            + "VALUES (#{poolKey}, 0, #{instanceTag})")
    int seedGlobalPool(@Param("table") String table,
                      @Param("poolKey") String poolKey,
                      @Param("instanceTag") String instanceTag);

    /**
     * 原子取号：LAST_INSERT_ID(worker_id + 1) 自增并绑回会话变量。
     * <b>必须与 {@link #selectLastInsertId} 在同一连接/事务内执行</b>。
     *
     * @param table       表名
     * @param instanceTag 实例标记
     * @param poolKey     全局池 key
     * @return 影响行数
     */
    @Update("UPDATE ${table} SET worker_id = LAST_INSERT_ID(worker_id + 1), "
            + "instance = #{instanceTag}, updated_at = NOW() WHERE service_name = #{poolKey}")
    int takeFromGlobalPool(@Param("table") String table,
                           @Param("instanceTag") String instanceTag,
                           @Param("poolKey") String poolKey);

    /**
     * 会话内取回上次 LAST_INSERT_ID 值。<b>须与 takeFromGlobalPool 同连接</b>。
     *
     * @return 当前连接的 LAST_INSERT_ID
     */
    @Select("SELECT LAST_INSERT_ID()")
    long selectLastInsertId();

    /**
     * 记录/更新服务实例占用审计行（upsert）。
     *
     * @param table       表名
     * @param serviceName 服务名
     * @param workerId    机器位
     * @param instanceTag 实例标记
     * @return 影响行数
     */
    @Insert("INSERT INTO ${table} (service_name, worker_id, instance, updated_at) "
            + "VALUES (#{serviceName}, #{workerId}, #{instanceTag}, NOW()) "
            + "ON DUPLICATE KEY UPDATE worker_id = #{workerId}, "
            + "instance = #{instanceTag}, updated_at = NOW()")
    int recordOwnership(@Param("table") String table,
                        @Param("serviceName") String serviceName,
                        @Param("workerId") long workerId,
                        @Param("instanceTag") String instanceTag);

    /**
     * 回绕重置全局池为 0。
     *
     * @param table       表名
     * @param instanceTag 重置者标记
     * @param poolKey     全局池 key
     * @return 影响行数
     */
    @Update("UPDATE ${table} SET worker_id = 0, instance = #{instanceTag} "
            + "WHERE service_name = #{poolKey}")
    int resetGlobalPool(@Param("table") String table,
                        @Param("instanceTag") String instanceTag,
                        @Param("poolKey") String poolKey);

    /**
     * 幂等建表。
     *
     * @param table 表名
     * @return 影响行数
     */
    @Update("CREATE TABLE IF NOT EXISTS ${table} ("
            + "service_name VARCHAR(64) NOT NULL PRIMARY KEY, "
            + "worker_id INT NOT NULL DEFAULT 0, "
            + "instance VARCHAR(128), "
            + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)")
    int createTable(@Param("table") String table);
}
