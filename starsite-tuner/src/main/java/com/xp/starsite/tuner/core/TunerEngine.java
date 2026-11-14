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
package com.xp.starsite.tuner.core;

import com.xp.starsite.schedule.trigger.CronTrigger;
import com.xp.starsite.tuner.api.PoolSnapshot;
import com.xp.starsite.tuner.api.TunerAuditEntry;
import com.xp.starsite.tuner.api.TunerClient;
import com.xp.starsite.tuner.api.TunerPreset;
import com.xp.starsite.tuner.api.TunerWindow;
import com.xp.starsite.tuner.clock.TimeSource;
import com.xp.starsite.tuner.store.TunerStore;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 调参引擎（{@link TunerClient} 门面实现 + 轮询求值主体）。
 *
 * <p>生效语义（设计核心，docs/tuner-design.md）：<b>窗口命中是纯时间函数</b>——
 * {@code active(t) ⟺ startCron.nextAfter(t) > endCron.nextAfter(t)}（下一次开始晚于
 * 下一次结束 ⟺ 上一次结束早于上一次开始 ⟺ 当前在窗口内）。多实例各自对同一时间求值
 * 天然收敛，无推送无 misfire。每轮 poll：version 变更才重载配置，窗口求值每轮都做
 * （时间是自变量）；求值结果与池现值对比，有差异才调 setter（收缩先 max 后 core），
 * 实际变更写审计（per-host）。
 *
 * @author muyi
 * @date 2026/11/14
 */
public class TunerEngine implements TunerClient {

    private static final Logger log = LoggerFactory.getLogger(TunerEngine.class);

    /** 受管池（name → executor） */
    private final Map<String, ThreadPoolExecutor> pools = new ConcurrentHashMap<>();

    /** 最近应用参数（name → [core,max,keep]）——no-op 去重 */
    private final Map<String, int[]> lastApplied = new ConcurrentHashMap<>();

    /** 最近应用时刻 */
    private final Map<String, LocalDateTime> lastAppliedAt = new ConcurrentHashMap<>();

    /** per-pool 应用锁（并发 applyAll 串行化——审计不重复） */
    private final Map<String, Object> applyLocks = new ConcurrentHashMap<>();

    /** 治理存储（共享参数源） */
    private final TunerStore store;

    /** 时间源（窗口求值与审计时刻） */
    private final TimeSource time;

    /** cron 求值时区（窗口语义） */
    private final java.time.ZoneId zone;

    /** 本实例标识（hostname:pid，审计 per-host 留痕） */
    private final String host;

    /**
     * 构造。
     *
     * @param store 治理存储
     * @param time  时间源
     * @param zone  窗口 cron 求值时区
     */
    public TunerEngine(TunerStore store, TimeSource time, java.time.ZoneId zone) {
        this.store = store;
        this.time = time;
        this.zone = zone;
        this.host = resolveHost();
    }

    /** {@inheritDoc} */
    @Override
    public void register(String poolName, ThreadPoolExecutor executor) {
        if (poolName == null || poolName.isBlank()) {
            throw new IllegalArgumentException("受管池名不可为空");
        }
        if (executor == null) {
            throw new IllegalArgumentException("受管线程池不可为 null: " + poolName);
        }
        pools.put(poolName.trim(), executor);
        // 启动基线对齐：注册即求值一次（宕机跨窗口边界的补偿——状态式求值无 misfire）
        applyAll(TunerAuditEntry.Source.STARTUP);
    }

    /** {@inheritDoc} */
    @Override
    public List<PoolSnapshot> pools() {
        List<PoolSnapshot> out = new ArrayList<>();
        for (Map.Entry<String, ThreadPoolExecutor> e : pools.entrySet()) {
            ThreadPoolExecutor p = e.getValue();
            String target = resolveTarget(e.getKey(), time.now())
                    .map(TunerPreset::getPresetName).orElse("baseline");
            out.add(new PoolSnapshot(e.getKey(), p.getCorePoolSize(), p.getMaximumPoolSize(),
                    (int) p.getKeepAliveTime(TimeUnit.SECONDS), p.getActiveCount(),
                    p.getQueue().size(), target, lastAppliedAt.get(e.getKey())));
        }
        return out;
    }

    /** {@inheritDoc} 求值与应用的完整一轮（与自动轮询同路径，无旁路） */
    @Override
    public void reload() {
        applyAll(TunerAuditEntry.Source.MANUAL);
    }

    /** {@inheritDoc} */
    @Override
    public TunerPreset upsertPreset(TunerPreset preset) {
        preset.validate();
        store.upsertPreset(preset);
        log.info("[starsite-tuner] 预案落库: {} (core={}, max={}, keep={}s)",
                preset.getPresetName(), preset.getCoreSize(), preset.getMaxSize(),
                preset.getKeepAliveSeconds());
        return preset;
    }

    /** {@inheritDoc} 写入期校验预案存在（fail-fast——绑定到不存在预案是配置错误） */
    @Override
    public void bindDefault(String poolName, String presetName) {
        if (poolName == null || poolName.isBlank() || presetName == null || presetName.isBlank()) {
            throw new IllegalArgumentException("池名与预案名不可为空");
        }
        if (store.preset(presetName.trim()).isEmpty()) {
            throw new IllegalArgumentException("预案不存在，先 upsertPreset: " + presetName);
        }
        store.bindDefault(poolName.trim(), presetName.trim());
    }

    /** {@inheritDoc} 写入期校验 cron 合法与预案存在（fail-fast 暴露在治理面而非运行期） */
    @Override
    public TunerWindow upsertWindow(TunerWindow window) {
        validateWindow(window);
        TunerWindow saved = store.upsertWindow(window);
        log.info("[starsite-tuner] 窗口落库: pool={} preset={} [{} ~ {}]", saved.getPoolName(),
                saved.getPresetName(), saved.getStartCron(), saved.getEndCron());
        return saved;
    }

    /** {@inheritDoc} */
    @Override
    public void removeWindow(long windowId) {
        store.removeWindow(windowId);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<TunerPreset> preset(String name) {
        return store.preset(name);
    }

    /** {@inheritDoc} */
    @Override
    public List<TunerWindow> windows(String poolName) {
        return store.windows(poolName);
    }

    /** {@inheritDoc} */
    @Override
    public List<TunerAuditEntry> recentAudits(int limit) {
        return store.recentAudits(limit);
    }

    /**
     * 求值并应用一轮（全部受管池）：窗口求值 → 目标预案 → 与现值对比 → 条件应用 + 审计。
     *
     * @param source 触发源（审计留痕）
     */
    public void applyAll(TunerAuditEntry.Source source) {
        LocalDateTime now = time.now();
        for (Map.Entry<String, ThreadPoolExecutor> e : pools.entrySet()) {
            applyPool(e.getKey(), e.getValue(), now, source);
        }
    }

    /**
     * 单池求值与应用：目标预案 = 命中窗口（剩余最长者）> 默认绑定 > 不动。
     *
     * @param poolName 池名
     * @param pool     线程池
     * @param now      当前时刻
     * @param source   触发源
     */
    private void applyPool(String poolName, ThreadPoolExecutor pool, LocalDateTime now,
                           TunerAuditEntry.Source source) {
        // per-pool 串行化：poll/admin/register 三路并发 applyAll 时，"读现值→比 lastApplied
        // →调 setter→写审计"必须原子——否则双读旧值会产生重复审计行（假变更记录，
        // 污染"审计只留真变更"承诺；P3C 巡检轮用户质询并发安全时实证补修）
        synchronized (applyLocks.computeIfAbsent(poolName, k -> new Object())) {
            doApplyPool(poolName, pool, now, source);
        }
    }

    /**
     * 单池求值与应用主体（per-pool 锁内执行）。
     *
     * @param poolName 池名
     * @param pool     线程池
     * @param now      当前时刻
     * @param source   触发源
     */
    private void doApplyPool(String poolName, ThreadPoolExecutor pool, LocalDateTime now,
                             TunerAuditEntry.Source source) {
        Optional<TunerPreset> target = resolveTarget(poolName, now);
        if (target.isEmpty()) {
            return; // 无绑定无窗口——保持基线（不猜）
        }
        TunerPreset preset = target.get();
        int[] current = {pool.getCorePoolSize(), pool.getMaximumPoolSize(),
                (int) pool.getKeepAliveTime(TimeUnit.SECONDS)};
        int[] wanted = {preset.getCoreSize(), preset.getMaxSize(), preset.getKeepAliveSeconds()};
        int[] last = lastApplied.get(poolName);
        if (java.util.Arrays.equals(current, wanted)
                || (last != null && java.util.Arrays.equals(last, wanted) && current[0] == wanted[0])) {
            return; // no-op 去重（账面已到位）
        }
        // 条件应用（JDK 约束恒 core<=max，双向各自有先后）：
        // 扩张/天花板够高 → 先抬 max 再抬 core；收缩到当前 core 之下 → 先降 core 再降 max
        if (wanted[1] >= pool.getCorePoolSize()) {
            pool.setMaximumPoolSize(wanted[1]);
            pool.setCorePoolSize(wanted[0]);
        } else {
            pool.setCorePoolSize(wanted[0]);
            pool.setMaximumPoolSize(wanted[1]);
        }
        pool.setKeepAliveTime(wanted[2], TimeUnit.SECONDS);
        lastApplied.put(poolName, wanted);
        lastAppliedAt.put(poolName, now);
        TunerAuditEntry audit = new TunerAuditEntry(null, now, host, poolName,
                preset.getPresetName(), source,
                current[0], current[1], current[2], wanted[0], wanted[1], wanted[2]);
        try {
            store.appendAudit(audit);
        } catch (Exception ex) {
            log.warn("[starsite-tuner] 审计落库失败（变更已生效）: {}", ex.getMessage());
        }
        log.info("[starsite-tuner] 池参数变更: pool={} preset={} source={} core {}→{} max {}→{} keep {}→{}s",
                poolName, preset.getPresetName(), source,
                current[0], wanted[0], current[1], wanted[1], current[2], wanted[2]);
    }

    /**
     * 目标预案解析：命中窗口（剩余最长者）优先于默认绑定；两者皆无为 empty（保持基线）。
     *
     * @param poolName 池名
     * @param now      当前时刻
     * @return 目标预案
     */
    private Optional<TunerPreset> resolveTarget(String poolName, LocalDateTime now) {
        TunerWindow best = null;
        long bestRemain = Long.MIN_VALUE;
        for (TunerWindow w : store.windows(poolName)) {
            if (insideWindow(w, now)) {
                long remain = fire(w.getEndCron(), now);
                if (remain > bestRemain) {
                    bestRemain = remain;
                    best = w;
                }
            }
        }
        String presetName = best != null ? best.getPresetName()
                : store.defaultPreset(poolName).orElse(null);
        if (presetName == null) {
            return Optional.empty();
        }
        Optional<TunerPreset> preset = store.preset(presetName);
        if (preset.isEmpty()) {
            log.warn("[starsite-tuner] 预案缺失（绑定/窗口引用了不存在的预案——治理配置被外部改动？）: {}",
                    presetName);
        }
        return preset;
    }

    /**
     * 窗口命中判定（纯时间函数）：{@code start.nextAfter(t) > end.nextAfter(t)}。
     *
     * @param w  窗口
     * @param now 当前时刻
     * @return true 在窗口内
     */
    private boolean insideWindow(TunerWindow w, LocalDateTime now) {
        long nextStart = fire(w.getStartCron(), now);
        long nextEnd = fire(w.getEndCron(), now);
        return nextStart > nextEnd;
    }

    /**
     * cron 下次触发（毫秒时间戳；解析失败按"永不触发"处理——治理配置错误不拖垮业务池，
     * WARN 可见，写入期校验已拦截绝大多数）。
     *
     * @param cron cron 表达式
     * @param now  基准时刻
     * @return 下次触发毫秒；解析失败为 Long.MAX_VALUE
     */
    private long fire(String cron, LocalDateTime now) {
        try {
            LocalDateTime next = new CronTrigger(cron, zone).nextAfter(now);
            return next == null ? Long.MAX_VALUE
                    : next.atZone(zone).toInstant().toEpochMilli();
        } catch (Exception e) {
            log.warn("[starsite-tuner] cron 求值失败（按永不触发处理）: {} - {}", cron, e.getMessage());
            return Long.MAX_VALUE;
        }
    }

    /**
     * 窗口写入期校验：池名/预案名非空、cron 可解析、预案存在。
     *
     * @param window 窗口
     */
    private void validateWindow(TunerWindow window) {
        if (window.getPoolName() == null || window.getPoolName().isBlank()
                || window.getPresetName() == null || window.getPresetName().isBlank()) {
            throw new IllegalArgumentException("窗口的池名与预案名不可为空");
        }
        // 校验顺序：cron 语法（纯本地）先行，预案存在性（存储查询）在后
        try {
            new CronTrigger(window.getStartCron(), zone);
            new CronTrigger(window.getEndCron(), zone);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "窗口 cron 非法: " + window.getStartCron() + " / " + window.getEndCron(), e);
        }
        if (store.preset(window.getPresetName()).isEmpty()) {
            throw new IllegalArgumentException("预案不存在，先 upsertPreset: " + window.getPresetName());
        }
    }

    /** hostname:pid（观测留痕，与 schedule/idempotent 同形） */
    private static String resolveHost() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "localhost";
        }
        String pid = ManagementFactory.getRuntimeMXBean().getName();
        int at = pid.indexOf('@');
        return hostname + ":" + (at > 0 ? pid.substring(0, at) : pid);
    }
}
