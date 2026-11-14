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
package com.xp.starsite.tuner.admin;
import com.xp.starsite.tuner.api.TunerClient;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 治理端点（默认关；{@code admin-enabled=true} 且 Web 应用才装配）：
 * 视图（pools/presets/windows/audit）+ 写操作（preset/binding/window/reload）。
 * <p>类级 {@code @ResponseBody} 而非 {@code @RestController}——后者是 stereotype 会被
 * 宿主宽扫描直接实例化、绕过条件装配（BUG-008 实证教训，家族铁律）。
 *
 * @author muyi
 * @date 2026/11/14
 */
@ResponseBody
@RequestMapping("${starsite.tuner.admin-base-path:/api/admin/tuner}")
public class TunerAdminController {

    /** 治理门面 */
    private final TunerClient client;

    /** 守卫（可 null 语义：无守卫配置时放行——装配期已 WARN） */
    private final TunerAdminGuard guard;

    /**
     * 构造。
     *
     * @param client 治理门面
     * @param guard  守卫（可 null）
     */
    public TunerAdminController(TunerClient client, TunerAdminGuard guard) {
        this.client = client;
        this.guard = guard;
    }

    /**
     * 治理视图：全部受管池快照。
     *
     * @param request 请求（守卫判定）
     * @return 快照列表
     */
    @GetMapping("/pools")
    public List<PoolSnapshotView> pools(@org.springframework.web.bind.annotation.RequestHeader java.util.Map<String, String> headers) {
        return guard(headers) ? toViews(client.pools()) : Arrays.asList();
    }

    /**
     * 最近审计。
     *
     * @param request 请求
     * @param limit   条数（默认 20）
     * @return 审计列表
     */
    @GetMapping("/audit")
    public List<com.xp.starsite.tuner.api.TunerAuditEntry> audit(
            @org.springframework.web.bind.annotation.RequestHeader java.util.Map<String, String> headers,
            @RequestParam(defaultValue = "20") int limit) {
        return guard(headers) ? client.recentAudits(limit) : Arrays.asList();
    }

    /**
     * upsert 预案。
     *
     * @param request 请求
     * @param body    预案字段（name/core/max/keepAliveSeconds）
     * @return 结果
     */
    @PostMapping("/preset")
    public Map<String, Object> upsertPreset(@org.springframework.web.bind.annotation.RequestHeader java.util.Map<String, String> headers,
                                            @RequestBody Map<String, Object> body) {
        if (!guard(headers)) {
            return mapOf("ok", false, "message", "forbidden");
        }
        Object queueRaw = body.get("queueCapacity");
        com.xp.starsite.tuner.api.TunerPreset preset = new com.xp.starsite.tuner.api.TunerPreset(
                String.valueOf(body.get("name")),
                ((Number) body.get("core")).intValue(),
                ((Number) body.get("max")).intValue(),
                ((Number) body.getOrDefault("keepAliveSeconds", 60)).intValue(),
                queueRaw == null ? null : ((Number) queueRaw).intValue());
        client.upsertPreset(preset);
        return mapOf("ok", true);
    }

    /**
     * 绑定池默认预案。
     *
     * @param request 请求
     * @param body    poolName/presetName
     * @return 结果
     */
    @PostMapping("/binding")
    public Map<String, Object> bind(@org.springframework.web.bind.annotation.RequestHeader java.util.Map<String, String> headers,
                                    @RequestBody Map<String, String> body) {
        if (!guard(headers)) {
            return mapOf("ok", false, "message", "forbidden");
        }
        client.bindDefault(body.get("poolName"), body.get("presetName"));
        return mapOf("ok", true);
    }

    /**
     * upsert 窗口。
     *
     * @param request 请求
     * @param body    poolName/presetName/startCron/endCron
     * @return 结果
     */
    @PostMapping("/window")
    public Map<String, Object> upsertWindow(@org.springframework.web.bind.annotation.RequestHeader java.util.Map<String, String> headers,
                                            @RequestBody Map<String, String> body) {
        if (!guard(headers)) {
            return mapOf("ok", false, "message", "forbidden");
        }
        com.xp.starsite.tuner.api.TunerWindow saved = client.upsertWindow(new com.xp.starsite.tuner.api.TunerWindow(
                body.get("poolName"), body.get("presetName"),
                body.get("startCron"), body.get("endCron")));
        return mapOf("ok", true, "id", saved.getId());
    }

    /**
     * 手动触发一轮求值应用（与自动轮询同路径）。
     *
     * @param request 请求
     * @return 结果
     */
    @PostMapping("/reload")
    public Map<String, Object> reload(@org.springframework.web.bind.annotation.RequestHeader java.util.Map<String, String> headers) {
        if (!guard(headers)) {
            return mapOf("ok", false, "message", "forbidden");
        }
        client.reload();
        return mapOf("ok", true);
    }

    /** 守卫判定（guard 为 null = 装配期已 WARN 的放行形态） */
    private boolean guard(@org.springframework.web.bind.annotation.RequestHeader java.util.Map<String, String> headers) {
        return guard == null || guard.allow(headers);
    }

    /** 快照视图（供 JSON 序列化的平铺形态） */
    private List<PoolSnapshotView> toViews(List<com.xp.starsite.tuner.api.PoolSnapshot> pools) {
        return pools.stream().map(p -> new PoolSnapshotView(p.getPoolName(), p.getCoreSize(),
                p.getMaxSize(), p.getKeepAliveSeconds(), p.getActiveCount(), p.getQueueDepth(),
                p.getTargetPreset(), p.getLastAppliedAt() == null ? null
                        : p.getLastAppliedAt().toString())).collect(java.util.stream.Collectors.toList());
    }

    /** JSON 视图（Jackson 序列化——getter 形态） */
    static class PoolSnapshotView {

        /** 池名 */
        final String poolName;

        /** 核心数 */
        final int coreSize;

        /** 最大数 */
        final int maxSize;

        /** 保活秒 */
        final int keepAliveSeconds;

        /** 活跃数 */
        final int activeCount;

        /** 队列积压 */
        final int queueDepth;

        /** 目标预案名 */
        final String targetPreset;

        /** 最近变更时刻 */
        final String lastAppliedAt;

        /**
         * 构造。
         *
         * @param poolName         池名
         * @param coreSize         核心数
         * @param maxSize          最大数
         * @param keepAliveSeconds 保活秒
         * @param activeCount      活跃数
         * @param queueDepth       队列积压
         * @param targetPreset     目标预案
         * @param lastAppliedAt    最近变更时刻
         */
        PoolSnapshotView(String poolName, int coreSize, int maxSize, int keepAliveSeconds,
                         int activeCount, int queueDepth, String targetPreset, String lastAppliedAt) {
            this.poolName = poolName;
            this.coreSize = coreSize;
            this.maxSize = maxSize;
            this.keepAliveSeconds = keepAliveSeconds;
            this.activeCount = activeCount;
            this.queueDepth = queueDepth;
            this.targetPreset = targetPreset;
            this.lastAppliedAt = lastAppliedAt;
        }

        /**
         * 获取池名。
         *
         * @return 池名
         */
        public String getPoolName() {
            return poolName;
        }

        /**
         * 获取核心数。
         *
         * @return 核心数
         */
        public int getCoreSize() {
            return coreSize;
        }

        /**
         * 获取最大数。
         *
         * @return 最大数
         */
        public int getMaxSize() {
            return maxSize;
        }

        /**
         * 获取保活秒。
         *
         * @return 保活秒
         */
        public int getKeepAliveSeconds() {
            return keepAliveSeconds;
        }

        /**
         * 获取活跃数。
         *
         * @return 活跃数
         */
        public int getActiveCount() {
            return activeCount;
        }

        /**
         * 获取队列积压。
         *
         * @return 积压数
         */
        public int getQueueDepth() {
            return queueDepth;
        }

        /**
         * 获取目标预案名。
         *
         * @return 预案名
         */
        public String getTargetPreset() {
            return targetPreset;
        }

        /**
         * 获取最近变更时刻。
         *
         * @return 时刻
         */
        public String getLastAppliedAt() {
            return lastAppliedAt;
        }
    }
    /**
     * JDK8 版 Map.of 替身（null 值安全——巡检第 3 尺：Map.of 不接受 null）。
     *
     * @param kv 键值对序列（偶数长度）
     * @return 不可变映射
     */
    private static java.util.Map<String, Object> mapOf(Object... kv) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }
}
