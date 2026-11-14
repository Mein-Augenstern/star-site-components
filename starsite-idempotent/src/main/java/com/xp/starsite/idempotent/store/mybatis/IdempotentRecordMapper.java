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
package com.xp.starsite.idempotent.store.mybatis;

import com.xp.starsite.idempotent.api.IdempotentRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 幂等表 MyBatis 映射（可选路径：消费方 classpath 自带 mybatis 时由 starter 零配置切换；
 * XML 在同包路径 resources 下——MyBatis 官方约定按接口 FQN 自动加载，D3 决策）。
 *
 * @author muyi
 * @date 2026/11/12
 */
public interface IdempotentRecordMapper {

    /**
     * 建表（幂等；表名经白名单校验后由 XML ${table} 渲染）。
     *
     * @param table 表名
     */
    void createTable(@Param("table") String table);

    /**
     * 认领插入（uk 冲突抛 PersistenceException——由 store 翻译为 duplicate）。
     *
     * @param table 表名
     * @param row   PROCESSING 行（id 已预生成）
     * @return 影响行数
     */
    int insert(@Param("table") String table, @Param("row") IdempotentRecord row);

    /**
     * 键点查详情（全列——result/error 详情列，本组件无列表查询）。
     *
     * @param table   表名
     * @param scope   键作用域
     * @param idemKey 幂等键
     * @return 记录；不存在为 null
     */
    IdempotentRecord findByKey(@Param("table") String table, @Param("scope") String scope,
                               @Param("idemKey") String idemKey);

    /**
     * 条件回收（三合一：FAILED / expire_at 过期 / PROCESSING 停滞——原子重置）。
     *
     * @param table       表名
     * @param scope       键作用域
     * @param idemKey     幂等键
     * @param expireAt    新窗口边界
     * @param host        回收实例
     * @param now         过期判定基准时刻
     * @param staleBefore PROCESSING 停滞阈值时刻
     * @return 影响行数（&gt;0 即回收成功）
     */
    int reclaim(@Param("table") String table, @Param("scope") String scope,
                @Param("idemKey") String idemKey, @Param("expireAt") LocalDateTime expireAt,
                @Param("host") String host, @Param("now") LocalDateTime now,
                @Param("staleBefore") LocalDateTime staleBefore);

    /**
     * 落成功终态（双条件守卫：PROCESSING + expire_at=本代认领值——代际令牌；
     * uk 自然键寻址）。
     *
     * @param table         表名
     * @param scope         键作用域
     * @param idemKey       幂等键
     * @param claimExpireAt 本代认领写入的 expire_at
     * @param result        回放载荷
     * @return 影响行数
     */
    int complete(@Param("table") String table, @Param("scope") String scope,
                 @Param("idemKey") String idemKey,
                 @Param("claimExpireAt") LocalDateTime claimExpireAt,
                 @Param("result") String result);

    /**
     * 落失败终态（双条件守卫同 complete）。
     *
     * @param table         表名
     * @param scope         键作用域
     * @param idemKey       幂等键
     * @param claimExpireAt 本代认领写入的 expire_at
     * @param error         失败摘要
     * @return 影响行数
     */
    int fail(@Param("table") String table, @Param("scope") String scope,
             @Param("idemKey") String idemKey,
             @Param("claimExpireAt") LocalDateTime claimExpireAt,
             @Param("error") String error);

    /**
     * 按状态计数（健康端点低频）。
     *
     * @param table  表名
     * @param status 状态名
     * @return 行数
     */
    long countByStatus(@Param("table") String table, @Param("status") String status);

    /**
     * 圈过期行主键（两步清理第一步）。
     *
     * @param table     表名
     * @param now       过期判定基准时刻
     * @param batchSize 批量上限
     * @return 主键列表
     */
    List<Long> selectExpiredIds(@Param("table") String table, @Param("now") LocalDateTime now,
                                @Param("batchSize") int batchSize);

    /**
     * 按主键集合删除（两步清理第二步；idList 非空由 store 保证——空集不走删除）。
     *
     * @param table 表名
     * @param ids   主键集合
     * @return 影响行数
     */
    int deleteByIds(@Param("table") String table, @Param("ids") List<Long> ids);

    /**
     * 查表列名（结构校验用——BUG-009：表存在但缺契约列时启动期 fail-fast；
     * 走 information_schema 元数据，表名参数绑定 + 大小写无关比对）。
     *
     * @param table 表名
     * @return 列名列表；表不存在为空
     */
    List<String> selectColumns(@Param("table") String table);
}
