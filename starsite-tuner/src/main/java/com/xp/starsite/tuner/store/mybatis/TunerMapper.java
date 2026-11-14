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
package com.xp.starsite.tuner.store.mybatis;

import com.xp.starsite.tuner.api.TunerAuditEntry;
import com.xp.starsite.tuner.api.TunerPreset;
import com.xp.starsite.tuner.api.TunerWindow;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 治理三表 MyBatis 映射（可选路径；XML 同包资源按 FQN 自动加载，D3 决策）。
 *
 * @author muyi
 * @date 2026/11/14
 */
public interface TunerMapper {

    /**
     * 建预案表。
     *
     * @param table 表名
     */
    void createPresetTable(@Param("table") String table);

    /**
     * 建窗口表。
     *
     * @param table 表名
     */
    void createWindowTable(@Param("table") String table);

    /**
     * 建绑定表。
     *
     * @param table 表名
     */
    void createBindingTable(@Param("table") String table);

    /**
     * 建审计表。
     *
     * @param table 表名
     */
    void createAuditTable(@Param("table") String table);

    /**
     * upsert 预案。
     *
     * @param table 表名
     * @param seq   主键（sequence 发号；uk 冲突时未使用）
     * @param row   预案
     * @return 影响行数
     */
    int upsertPreset(@Param("table") String table, @Param("seq") long seq,
                     @Param("row") TunerPreset row);

    /**
     * 预案点查。
     *
     * @param table 表名
     * @param name  预案名
     * @return 预案；null 不存在
     */
    TunerPreset findPreset(@Param("table") String table, @Param("name") String name);

    /**
     * 全部预案。
     *
     * @param table 表名
     * @return 预案列表
     */
    List<TunerPreset> listPresets(@Param("table") String table);

    /**
     * upsert 绑定。
     *
     * @param table      表名
     * @param seq        主键（sequence 发号；uk 冲突时未使用）
     * @param poolName   池名
     * @param presetName 预案名
     * @return 影响行数
     */
    int upsertBinding(@Param("table") String table, @Param("seq") long seq,
                      @Param("poolName") String poolName,
                      @Param("presetName") String presetName);

    /**
     * 绑定点查。
     *
     * @param table    表名
     * @param poolName 池名
     * @return 预案名；null 未绑定
     */
    String findBinding(@Param("table") String table, @Param("poolName") String poolName);

    /**
     * 绑定计数。
     *
     * @param table 表名
     * @return 绑定数
     */
    long countBindings(@Param("table") String table);

    /**
     * 新增窗口。
     *
     * @param table 表名
     * @param id    id（sequence 发号）
     * @param w     窗口
     * @return 影响行数
     */
    int insertWindow(@Param("table") String table, @Param("id") long id, @Param("w") TunerWindow w);

    /**
     * 更新窗口。
     *
     * @param table 表名
     * @param w     窗口（含 id）
     * @return 影响行数
     */
    int updateWindow(@Param("table") String table, @Param("w") TunerWindow w);

    /**
     * 删除窗口。
     *
     * @param table 表名
     * @param id    窗口 id
     * @return 影响行数
     */
    int removeWindow(@Param("table") String table, @Param("id") long id);

    /**
     * 窗口清单（pool 为 null 查全部）。
     *
     * @param table    表名
     * @param poolName 池名（可 null）
     * @return 窗口列表
     */
    List<TunerWindow> listWindows(@Param("table") String table, @Param("poolName") String poolName);

    /**
     * 追加审计。
     *
     * @param table 表名
     * @param row   审计条目（id 已发号）
     * @return 影响行数
     */
    int appendAudit(@Param("table") String table, @Param("row") TunerAuditEntry row);

    /**
     * 最近审计。
     *
     * @param table 表名
     * @param limit 条数
     * @return 审计列表
     */
    List<TunerAuditEntry> recentAudits(@Param("table") String table, @Param("limit") int limit);

    /**
     * 配置版本戳（三表 gmt_modified 最大值）。
     *
     * @param presetTable  预案表
     * @param windowTable  窗口表
     * @param bindingTable 绑定表
     * @param auditTable   审计表
     * @return 版本戳字符串（ISO 时刻）；无行为 null
     */
    String versionStamp(@Param("presetTable") String presetTable,
                        @Param("windowTable") String windowTable,
                        @Param("bindingTable") String bindingTable,
                        @Param("auditTable") String auditTable);
}
