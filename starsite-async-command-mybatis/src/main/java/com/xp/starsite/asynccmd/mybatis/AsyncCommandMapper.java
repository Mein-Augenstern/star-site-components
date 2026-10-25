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
package com.xp.starsite.asynccmd.mybatis;

import com.xp.starsite.asynccmd.model.AsyncCommand;
import com.xp.starsite.asynccmd.model.CommandStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 异步命令 MyBatis Mapper（注解式——SQL 与参数绑定即框架原生形态，消费方可直接复用）。
 * <p>
 * 设计约定：
 * <ul>
 *   <li>表名参数 {@code ${table}} 经 MyBatis 字符串替换（表名在 Store 构造时已过白名单校验防注入），
 *       业务值一律 {@code #{...}} 参数绑定</li>
 *   <li>实体字段与表列的下划线/驼峰映射依赖 MyBatis 全局配置
 *       {@code map-underscore-to-camel-case=true}（spring-boot mybatis-starter 默认开启）</li>
 *   <li>状态列为字符串 code，经 {@link CommandStatus#fromCode} / {@link CommandStatus#getCode}
 *       与枚举互转（转逻辑在 Store 层做，Mapper 保持纯净的表映射）</li>
 * </ul>
 *
 * @author muyi
 * @date 2026/10/25
 */
@org.apache.ibatis.annotations.Mapper
public interface AsyncCommandMapper {

    /**
     * 新建命令（自增 id 回填实体）。
     *
     * @param table 表名（白名单校验后的字符串替换）
     * @param cmd   命令实体（id 由数据库分配回填）
     * @return 影响行数
     */
    @Insert("INSERT INTO ${table} (cmd_type, biz_key, payload, status, max_retry, retry_count, "
            + "next_run_at, owner) VALUES (#{cmd.cmdType}, #{cmd.bizKey}, #{cmd.payload}, "
            + "#{status}, #{cmd.maxRetry}, #{cmd.retryCount}, #{cmd.nextRunAt}, #{cmd.owner})")
    @Options(useGeneratedKeys = true, keyProperty = "cmd.id", keyColumn = "id")
    int insert(@Param("table") String table, @Param("cmd") AsyncCommand cmd,
               @Param("status") String status);

    /**
     * 按主键查询。
     *
     * @param table 表名
     * @param id    主键
     * @return 命令行；不存在返回 null
     */
    @Select("SELECT * FROM ${table} WHERE id = #{id}")
    AsyncCommand selectById(@Param("table") String table, @Param("id") long id);

    /**
     * 幂等检查：同 type+key 未完成计数。
     *
     * @param table  表名
     * @param cmdType 命令类型
     * @param bizKey  业务键
     * @return 未完成条数
     */
    @Select("SELECT COUNT(*) FROM ${table} WHERE cmd_type = #{cmdType} AND biz_key = #{bizKey} "
            + "AND status IN ('PENDING', 'RUNNING')")
    long countUnfinished(@Param("table") String table,
                         @Param("cmdType") String cmdType, @Param("bizKey") String bizKey);

    /**
     * 批量令牌认领第 1 步（派生表绕 MySQL ERROR 1093）。
     *
     * @param table 表名
     * @param token 认领令牌
     * @param now   到期判定时间
     * @param limit 认领上限
     * @return 认领条数
     */
    @Update("UPDATE ${table} SET status = 'RUNNING', claim_token = #{token}, updated_at = NOW() "
            + "WHERE status = 'PENDING' AND next_run_at <= #{now} "
            + "AND id IN (SELECT id FROM (SELECT id FROM ${table} "
            + "WHERE status = 'PENDING' AND next_run_at <= #{now} ORDER BY id LIMIT #{limit}) t)")
    int claim(@Param("table") String table, @Param("token") String token,
              @Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 批量令牌认领第 2 步：按令牌取回。
     *
     * @param table 表名
     * @param token 认领令牌
     * @return 本轮认领的命令列表（id 升序）
     */
    @Select("SELECT * FROM ${table} WHERE claim_token = #{token} ORDER BY id")
    List<AsyncCommand> findByToken(@Param("table") String table, @Param("token") String token);

    /**
     * 按 id 原子认领（IMMEDIATE 模式）。
     *
     * @param table 表名
     * @param token 令牌
     * @param id    命令主键
     * @return 认领条数（&gt;0 即成功）
     */
    @Update("UPDATE ${table} SET status = 'RUNNING', claim_token = #{token}, updated_at = NOW() "
            + "WHERE id = #{id} AND status = 'PENDING' AND next_run_at <= NOW()")
    int claimById(@Param("table") String table, @Param("token") String token, @Param("id") long id);

    /**
     * 崩溃回收：悬挂 RUNNING 回 PENDING（阈值时间由调用方计算后参数绑定——H2/MySQL 通用）。
     *
     * @param table       表名
     * @param threshold   悬挂判定阈值时刻（updated_at 早于此时刻即回收；由 Store 以应用时钟计算）
     * @param limit       单轮上限
     * @return 回收条数
     */
    @Update("UPDATE ${table} SET status = 'PENDING', claim_token = NULL, updated_at = NOW() "
            + "WHERE status = 'RUNNING' AND updated_at < #{threshold} "
            + "LIMIT #{limit}")
    int reclaimStale(@Param("table") String table,
                     @Param("threshold") LocalDateTime threshold, @Param("limit") int limit);

    /**
     * 状态回写。
     *
     * @param table      表名
     * @param cmd        命令（读 status/claimToken/retryCount/nextRunAt/lastError/id）
     * @param statusCode 目标状态存储码
     * @return 影响行数
     */
    @Update("UPDATE ${table} SET status = #{statusCode}, claim_token = #{cmd.claimToken}, "
            + "retry_count = #{cmd.retryCount}, next_run_at = #{cmd.nextRunAt}, "
            + "last_error = #{cmd.lastError}, updated_at = NOW() WHERE id = #{cmd.id}")
    int updateState(@Param("table") String table, @Param("cmd") AsyncCommand cmd,
                    @Param("statusCode") String statusCode);

    /**
     * 按状态计数。
     *
     * @param table     表名
     * @param statusCode 状态码
     * @return 条数
     */
    @Select("SELECT COUNT(*) FROM ${table} WHERE status = #{statusCode}")
    long countByStatus(@Param("table") String table, @Param("statusCode") String statusCode);

    /**
     * 到期积压计数。
     *
     * @param table 表名
     * @param now   到期判定时间
     * @return 条数
     */
    @Select("SELECT COUNT(*) FROM ${table} WHERE status = 'PENDING' AND next_run_at <= #{now}")
    long countBacklogDue(@Param("table") String table, @Param("now") LocalDateTime now);

    /**
     * 近 N 条（id 倒序）。
     *
     * @param table 表名
     * @param limit 上限
     * @return 命令列表
     */
    @Select("SELECT * FROM ${table} ORDER BY id DESC LIMIT #{limit}")
    List<AsyncCommand> selectRecent(@Param("table") String table, @Param("limit") int limit);

    /**
     * 管控分页计数（可选过滤）。
     *
     * @param table      表名
     * @param statusCode 状态过滤（null/空 = 不过滤）
     * @param cmdType    类型过滤（null/空 = 不过滤）
     * @return 条数
     */
    @Select("<script>SELECT COUNT(*) FROM ${table} WHERE 1 = 1"
            + "<if test='statusCode != null and statusCode != \"\"'> AND status = #{statusCode}</if>"
            + "<if test='cmdType != null and cmdType != \"\"'> AND cmd_type = #{cmdType}</if>"
            + "</script>")
    long countPage(@Param("table") String table,
                   @Param("statusCode") String statusCode, @Param("cmdType") String cmdType);

    /**
     * 管控分页查询（可选过滤 + id 倒序）。
     *
     * @param table      表名
     * @param statusCode 状态过滤
     * @param cmdType    类型过滤
     * @param offset     偏移量
     * @param size       页大小
     * @return 命令列表
     */
    @Select("<script>SELECT * FROM ${table} WHERE 1 = 1"
            + "<if test='statusCode != null and statusCode != \"\"'> AND status = #{statusCode}</if>"
            + "<if test='cmdType != null and cmdType != \"\"'> AND cmd_type = #{cmdType}</if>"
            + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<AsyncCommand> selectPage(@Param("table") String table,
                                  @Param("statusCode") String statusCode, @Param("cmdType") String cmdType,
                                  @Param("offset") long offset, @Param("size") int size);

    /**
     * 建表（H2/MySQL 兼容的幂等 DDL——消费方通常经迁移脚本管理，此为本地开发便捷）。
     *
     * @param table 表名
     * @return 影响行数
     */
    @Update("CREATE TABLE IF NOT EXISTS ${table} ("
            + "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
            + "cmd_type VARCHAR(64) NOT NULL, "
            + "biz_key VARCHAR(128) DEFAULT '', "
            + "payload TEXT, "
            + "status VARCHAR(16) NOT NULL DEFAULT 'PENDING', "
            + "max_retry INT NOT NULL DEFAULT 5, "
            + "retry_count INT NOT NULL DEFAULT 0, "
            + "next_run_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
            + "last_error VARCHAR(500), "
            + "owner VARCHAR(64) DEFAULT '', "
            + "claim_token VARCHAR(64), "
            + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
            + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)")
    int createTable(@Param("table") String table);
}
