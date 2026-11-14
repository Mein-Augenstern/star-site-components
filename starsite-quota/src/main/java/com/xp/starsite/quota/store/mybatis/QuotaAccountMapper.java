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
package com.xp.starsite.quota.store.mybatis;

import com.xp.starsite.quota.api.QuotaBalance;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;

/**
 * 配额表 MyBatis 映射（可选路径：消费方 classpath 自带 mybatis 时由 starter 零配置切换；
 * XML 在同包路径 resources 下——MyBatis 官方约定按接口 FQN 自动加载，D3 决策）。
 *
 * @author muyi
 * @date 2026/11/14
 */
public interface QuotaAccountMapper {

    /**
     * 建表（幂等；表名经白名单校验后由 XML ${table} 渲染）。
     *
     * @param table 表名
     */
    void createTable(@Param("table") String table);

    /**
     * 建户插入（uk 冲突抛 PersistenceException——由 store 翻译为已存在保留现量）。
     *
     * @param table      表名
     * @param id         主键（sequence 预生成）
     * @param quotaKey   配额键
     * @param total      总量
     * @param gmtCreate  建户时刻
     * @return 影响行数
     */
    int insert(@Param("table") String table, @Param("id") long id,
               @Param("quotaKey") String quotaKey, @Param("total") long total,
               @Param("gmtCreate") LocalDateTime gmtCreate);

    /**
     * 键点查账面。
     *
     * @param table    表名
     * @param quotaKey 配额键
     * @return 余额；不存在为 null
     */
    QuotaBalance findByKey(@Param("table") String table, @Param("quotaKey") String quotaKey);

    /**
     * 占用（条件 UPDATE：不变式在 WHERE 内原子判定）。
     *
     * @param table    表名
     * @param quotaKey 配额键
     * @param amount   占用量
     * @return 影响行数
     */
    int occupy(@Param("table") String table, @Param("quotaKey") String quotaKey,
               @Param("amount") long amount);

    /**
     * 释放占用。
     *
     * @param table    表名
     * @param quotaKey 配额键
     * @param amount   释放量
     * @return 影响行数
     */
    int release(@Param("table") String table, @Param("quotaKey") String quotaKey,
                @Param("amount") long amount);

    /**
     * 冻结。
     *
     * @param table    表名
     * @param quotaKey 配额键
     * @param amount   冻结量
     * @return 影响行数
     */
    int freeze(@Param("table") String table, @Param("quotaKey") String quotaKey,
               @Param("amount") long amount);

    /**
     * 解冻。
     *
     * @param table    表名
     * @param quotaKey 配额键
     * @param amount   解冻量
     * @return 影响行数
     */
    int unfreeze(@Param("table") String table, @Param("quotaKey") String quotaKey,
                 @Param("amount") long amount);

    /**
     * 冻结转占用（单语句双计数换位）。
     *
     * @param table    表名
     * @param quotaKey 配额键
     * @param amount   转销量
     * @return 影响行数
     */
    int confirm(@Param("table") String table, @Param("quotaKey") String quotaKey,
                @Param("amount") long amount);

    /**
     * 调整总量（降额保护条件在 WHERE）。
     *
     * @param table    表名
     * @param quotaKey 配额键
     * @param delta    增量
     * @return 影响行数
     */
    int adjustTotal(@Param("table") String table, @Param("quotaKey") String quotaKey,
                    @Param("delta") long delta);

    /**
     * 账户计数（健康端点低频）。
     *
     * @param table 表名
     * @return 账户数
     */
    long countAccounts(@Param("table") String table);

    /**
     * 查表列名（结构校验用——BUG-009：表存在但缺契约列时启动期 fail-fast）。
     *
     * @param table 表名（参数绑定 + 大小写无关比对）
     * @return 列名列表；表不存在为空
     */
    java.util.List<String> selectColumns(@Param("table") String table);
}
