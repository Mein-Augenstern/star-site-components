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
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 异步命令 MyBatis Mapper（接口与 SQL 分离——SQL 全部在
 * 同包 {@code AsyncCommandMapper.xml}，由 MyBatis 按命名空间自动发现加载）。
 * <p>
 * 约定：{@code ${table}} 为白名单校验后的表名字符串替换；业务值一律 {@code #{...}} 参数绑定。
 * 主键 id 由 starsite-sequence 组件生成（INSERT 显式带入，表无 AUTO_INCREMENT）。
 *
 * @author muyi
 * @date 2026/10/25
 */
@org.apache.ibatis.annotations.Mapper
public interface AsyncCommandMapper {

    /**
     * 新建命令（id 由 starsite-sequence 组件预生成后显式写入）。
     *
     * @param table 表名
     * @param cmd   命令实体（id 已分配）
     * @return 影响行数
     */
    int insert(@Param("table") String table, @Param("cmd") AsyncCommand cmd);

    /**
     * 按主键查询。
     *
     * @param table 表名
     * @param id    主键
     * @return 命令行；不存在返回 null
     */
    AsyncCommand selectById(@Param("table") String table, @Param("id") long id);

    /**
     * 幂等检查：同 type+key 未完成计数。
     *
     * @param table   表名
     * @param cmdType 命令类型
     * @param bizKey  业务键
     * @return 未完成条数
     */
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
    int claim(@Param("table") String table, @Param("token") String token,
              @Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 批量令牌认领第 2 步：按令牌取回。
     *
     * @param table 表名
     * @param token 认领令牌
     * @return 本轮认领的命令列表（id 升序）
     */
    List<AsyncCommand> findByToken(@Param("table") String table, @Param("token") String token);

    /**
     * 按 id 原子认领（IMMEDIATE 模式）。
     *
     * @param table 表名
     * @param token 令牌
     * @param id    命令主键
     * @return 认领条数（&gt;0 即成功）
     */
    int claimById(@Param("table") String table, @Param("token") String token, @Param("id") long id);

    /**
     * 崩溃回收（旧形态，单表专用）：悬挂 RUNNING 回 PENDING（UPDATE..LIMIT 不被分片
     * 中间件支持，分片形态用 {@link #selectStaleIds}+{@link #reclaimByIds} 两步法）。
     *
     * @param table     表名
     * @param threshold 悬挂判定阈值时刻
     * @param limit     单轮上限
     * @return 回收条数
     */
    int reclaimStale(@Param("table") String table,
                     @Param("threshold") LocalDateTime threshold, @Param("limit") int limit);

    /**
     * 崩溃回收两步法·第 1 步：选出悬挂 RUNNING 的 id（SELECT..LIMIT 分片友好）。
     *
     * @param table     表名
     * @param threshold 悬挂判定阈值时刻
     * @param limit     每轮上限（分片形态下每分片各取 limit 条）
     * @return 悬挂命令 id 列表
     */
    java.util.List<Long> selectStaleIds(@Param("table") String table,
                                        @Param("threshold") LocalDateTime threshold,
                                        @Param("limit") int limit);

    /**
     * 崩溃回收两步法·第 2 步：按显式 id 集回收（foreach 全参数绑定）。
     *
     * @param table 表名
     * @param ids   悬挂命令 id 集
     * @return 回收条数
     */
    int reclaimByIds(@Param("table") String table, @Param("ids") java.util.List<Long> ids);

    /**
     * 状态回写。
     *
     * @param table 表名
     * @param cmd   命令（读 status/claimToken/retryCount/nextRunAt/lastError/id）
     * @return 影响行数
     */
    int updateState(@Param("table") String table, @Param("cmd") AsyncCommand cmd);

    /**
     * 按状态计数。
     *
     * @param table      表名
     * @param statusCode 状态码
     * @return 条数
     */
    long countByStatus(@Param("table") String table, @Param("statusCode") String statusCode);

    /**
     * 到期积压计数。
     *
     * @param table 表名
     * @param now   到期判定时间
     * @return 条数
     */
    long countBacklogDue(@Param("table") String table, @Param("now") LocalDateTime now);

    /**
     * 近 N 条（id 倒序）。
     *
     * @param table 表名
     * @param limit 上限
     * @return 命令列表
     */
    List<AsyncCommand> selectRecent(@Param("table") String table, @Param("limit") int limit);

    /**
     * 管控分页计数（可选过滤）。
     *
     * @param table      表名
     * @param statusCode 状态过滤（null/空 = 不过滤）
     * @param cmdType    类型过滤（null/空 = 不过滤）
     * @return 条数
     */
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
    List<AsyncCommand> selectPage(@Param("table") String table,
                                  @Param("statusCode") String statusCode, @Param("cmdType") String cmdType,
                                  @Param("offset") long offset, @Param("size") int size);

    /**
     * 建表（MySQL 方言 DDL；生产用迁移脚本）。
     *
     * @param table 表名
     * @return 影响行数
     */
    int createTable(@Param("table") String table);

    /**
     * 结构校验（BUG-009）：列出命令表实际列名。表名经 {@code #{}} 参数绑定（非 {@code ${}}
     * 拼接）——元数据查询无注入面；表不存在返回空集（DBA 管理场景由调用方跳过校验）。
     *
     * @param tableName 表名
     * @return 实际列名清单（MySQL/H2 的 INFORMATION_SCHEMA 通用）
     */
    java.util.List<String> selectColumns(@Param("tableName") String tableName);
}
