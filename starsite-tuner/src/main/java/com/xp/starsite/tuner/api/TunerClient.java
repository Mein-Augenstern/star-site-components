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
package com.xp.starsite.tuner.api;

import java.util.Optional;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 调参治理门面（业务方唯一入口）：受管池注册 + 治理面读写 + 视图查询。
 * 实现为 core 的 TunerEngine（轮询/窗口求值/条件应用/审计全在里面）。
 *
 * @author muyi
 * @date 2026/11/14
 */
public interface TunerClient {

    /**
     * 注册受管池（幂等：同名覆盖引用）。注册时快照当前参数为基线——无绑定无窗口
     * 则永不改动（不猜）。
     *
     * @param poolName 池名（全局唯一，业务语义命名——channel-daikou/batch-settle）
     * @param executor 受管线程池
     * @throws IllegalArgumentException poolName 为空或 executor 为 null
     */
    void register(String poolName, ThreadPoolExecutor executor);

    /**
     * 全部受管池快照（实际参数 + 活跃度 + 当前应生效预案）。
     *
     * @return 快照列表（注册序）
     */
    java.util.List<PoolSnapshot> pools();

    /**
     * 立即重新评估并应用（admin 手动触发 / 测试用）——等价于把下一轮 poll 提前，
     * 语义与自动轮询完全一致（同一求值路径，无旁路）。
     */
    void reload();

    /**
     * upsert 预案（按名幂等；写入期校验参数合法 + 触发下轮重载）。
     *
     * @param preset 预案
     * @return 落库后的预案
     */
    TunerPreset upsertPreset(TunerPreset preset);

    /**
     * 绑定池默认预案（按池名幂等 upsert；写入期校验预案存在）。
     *
     * @param poolName   池名（未注册也允许——先配参数后建池的运维顺序）
     * @param presetName 预案名
     */
    void bindDefault(String poolName, String presetName);

    /**
     * 新增/更新窗口（同池同时段重叠会在写入期 WARN——治理面可见）。
     *
     * @param window 窗口（id=0 新增；带 id 更新）
     * @return 落库后的窗口（含 id）
     */
    TunerWindow upsertWindow(TunerWindow window);

    /**
     * 删除窗口。
     *
     * @param windowId 窗口 id
     */
    void removeWindow(long windowId);

    /**
     * 查询预案。
     *
     * @param name 预案名
     * @return 预案；不存在为 empty
     */
    Optional<TunerPreset> preset(String name);

    /**
     * 查询池的全部窗口。
     *
     * @param poolName 池名（null=全部池）
     * @return 窗口列表
     */
    java.util.List<TunerWindow> windows(String poolName);

    /**
     * 最近变更审计。
     *
     * @param limit 条数上限
     * @return 审计列表（新→旧）
     */
    java.util.List<TunerAuditEntry> recentAudits(int limit);
}
