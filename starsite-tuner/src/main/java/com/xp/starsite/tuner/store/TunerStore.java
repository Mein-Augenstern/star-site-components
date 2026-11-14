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
package com.xp.starsite.tuner.store;

import com.xp.starsite.tuner.api.TunerAuditEntry;
import com.xp.starsite.tuner.api.TunerPreset;
import com.xp.starsite.tuner.api.TunerWindow;
import java.util.List;
import java.util.Optional;

/**
 * 调参治理存储 SPI（三内置实现同语义等价——jdbc/mybatis 表形态、memory 单机形态）。
 *
 * <p><b>分布式语义的关键契约</b>：本 SPI 是多实例的<b>共享参数源</b>——所有实例
 * 轮询同一份数据，窗口求值（纯时间函数）后各自应用，天然收敛。实现必须保证
 * upsert 按名幂等（uk 冲突即更新）；versionStamp 单调（写后必变），供轮询短路。
 *
 * <p><b>第三方存储扩展点</b>：实现本接口注册 Bean 即接入（docs/extension.md L3 通道）。
 * 不提供 redis 形态是刻意的：治理数据（预案/审计）要 DB 留痕与事务，缓存形态无意义。
 *
 * @author muyi
 * @date 2026/11/14
 */
public interface TunerStore {

    /**
     * upsert 预案（按名幂等；调用方已校验参数合法性）。
     *
     * @param preset 预案
     */
    void upsertPreset(TunerPreset preset);

    /**
     * 查预案。
     *
     * @param name 预案名
     * @return 预案；不存在为 empty
     */
    Optional<TunerPreset> preset(String name);

    /**
     * 全部预案。
     *
     * @return 预案列表
     */
    List<TunerPreset> presets();

    /**
     * 绑定池默认预案（按池名 upsert）。
     *
     * @param poolName   池名
     * @param presetName 预案名
     */
    void bindDefault(String poolName, String presetName);

    /**
     * 查池默认预案。
     *
     * @param poolName 池名
     * @return 预案名；未绑定为 empty
     */
    Optional<String> defaultPreset(String poolName);

    /**
     * upsert 窗口（id=0 新增发号；带 id 更新）。
     *
     * @param window 窗口
     * @return 落库后的窗口（含 id）
     */
    TunerWindow upsertWindow(TunerWindow window);

    /**
     * 删除窗口。
     *
     * @param id 窗口 id
     */
    void removeWindow(long id);

    /**
     * 查窗口（poolName 为 null 查全部）。
     *
     * @param poolName 池名（null=全部）
     * @return 窗口列表
     */
    List<TunerWindow> windows(String poolName);

    /**
     * 追加审计（实际变更行）。
     *
     * @param entry 审计条目
     */
    void appendAudit(TunerAuditEntry entry);

    /**
     * 最近审计（新→旧）。
     *
     * @param limit 条数上限
     * @return 审计列表
     */
    List<TunerAuditEntry> recentAudits(int limit);

    /**
     * 配置版本戳（轮询短路依据；实现取四表 gmt_modified 最大值）。
     * <b>秒级精度契约</b>：表形态 DATETIME 秒精度——同秒内多次写可能同戳（无害，
     * 只是多一轮无害重载）；memory 形态严格单调。
     *
     * @return 版本戳（毫秒值；memory 为自增计数）
     */
    long versionStamp();

    /**
     * 受管池注册计数（健康端点；含已绑定但本实例未注册的池——治理面可见全局）。
     *
     * @return 绑定行数
     */
    long countBindings();

    /**
     * 清理到期审计行（V2 retention；两步法参数化删除——分片红线合规）。
     * <b>观测降级缺省</b>：memory 形态覆写实现；表形态覆写。
     *
     * @param batchSize 单轮批量上限
     * @param threshold 到期时刻（occurred_at 早于该时刻即清理）
     * @return 本轮清理行数
     */
    default long purgeExpiredAudits(int batchSize, java.time.LocalDateTime threshold) {
        return 0;
    }
}
