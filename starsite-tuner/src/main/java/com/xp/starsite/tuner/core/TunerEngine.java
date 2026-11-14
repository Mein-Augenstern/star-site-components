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
import com.xp.starsite.tuner.api.TunerAdaptiveRule;
import com.xp.starsite.tuner.api.TunerAlarm;
import com.xp.starsite.tuner.api.TunerAlarmHook;
import com.xp.starsite.tuner.api.TunerAuditEntry;
import com.xp.starsite.tuner.api.TunerChangeRequest;
import com.xp.starsite.tuner.api.TunerClient;
import com.xp.starsite.tuner.api.TunerPreset;
import com.xp.starsite.tuner.api.TunerWindow;
import com.xp.starsite.tuner.clock.TimeSource;
import com.xp.starsite.tuner.queue.ResizableCapacityLinkedBlockingQueue;
import com.xp.starsite.tuner.store.TunerStore;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 调参引擎 V2（{@link TunerClient} 门面实现 + 轮询求值主体）。
 *
 * <p>生效优先级：<b>自适应命中（冷却期内）&gt; 窗口 &gt; 默认绑定 &gt; 基线</b>。
 * 窗口命中是纯时间函数（startCron.nextAfter &gt; endCron.nextAfter）；每轮 poll：
 * version 变更才重载配置，窗口/阈值/自适应求值每轮都做（时间是自变量）；条件应用
 * （与现值对比 no-op 跳过；审批钩子是最后一道门；收缩先降 core 再降 max / 扩张先抬
 * max）；实际变更写审计（per-host）。
 *
 * @author muyi
 * @date 2026/11/14
 */
public class TunerEngine implements TunerClient {

    private static final Logger log = LoggerFactory.getLogger(TunerEngine.class);

    /** 受管池（name → executor） */
    private final Map<String, ThreadPoolExecutor> pools = new ConcurrentHashMap<>();

    /** 最近应用参数（name → [core,max,keep,queue]；queue=-1 表示不管理） */
    private final Map<String, int[]> lastApplied = new ConcurrentHashMap<>();

    /** 最近应用时刻 */
    private final Map<String, LocalDateTime> lastAppliedAt = new ConcurrentHashMap<>();

    /** per-pool 应用锁（三路 applyAll 并发串行化） */
    private final Map<String, Object> applyLocks = new ConcurrentHashMap<>();

    /** 队列容量不兼容警告只发一次（WARN 刷屏治理） */
    private final Set<String> queueWarned = ConcurrentHashMap.newKeySet();

    /** 自适应冷却（pool → 上次 ADAPTIVE 应用时刻） */
    private final Map<String, LocalDateTime> lastAdaptiveAt = new ConcurrentHashMap<>();

    /** 告警冷却（pool:type → 上次告警时刻） */
    private final Map<String, LocalDateTime> lastAlarmAt = new ConcurrentHashMap<>();

    /** 治理存储（共享参数源） */
    private final TunerStore store;

    /** 时间源 */
    private final TimeSource time;

    /** cron 求值时区 */
    private final java.time.ZoneId zone;

    /** 本实例标识 */
    private final String host;

    /** V2 选项（钩子/阈值/冷却——starter 装配；测试直接构造） */
    private final TunerOptions options;

    /**
     * 构造（V1 兼容形态：无钩子无自适应，家族缺省阈值）。
     *
     * @param store 治理存储
     * @param time  时间源
     * @param zone  时区
     */
    public TunerEngine(TunerStore store, TimeSource time, java.time.ZoneId zone) {
        this(store, time, zone, TunerOptions.defaults());
    }

    /**
     * 构造（V2 全参）。
     *
     * @param store   治理存储
     * @param time    时间源
     * @param zone    时区
     * @param options 钩子/阈值/冷却选项
     */
    public TunerEngine(TunerStore store, TimeSource time, java.time.ZoneId zone,
                       TunerOptions options) {
        this.store = store;
        this.time = time;
        this.zone = zone;
        this.options = options;
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
        // 启动基线对齐：注册即求值一次（状态式求值无 misfire）
        applyAll(TunerAuditEntry.Source.STARTUP);
    }

    /** {@inheritDoc} */
    @Override
    public List<PoolSnapshot> pools() {
        List<PoolSnapshot> out = new ArrayList<>();
        for (Map.Entry<String, ThreadPoolExecutor> e : pools.entrySet()) {
            ThreadPoolExecutor p = e.getValue();
            String target = resolveTargetBase(e.getKey(), time.now())
                    .map(TunerPreset::getPresetName).orElse("baseline");
            out.add(new PoolSnapshot(e.getKey(), p.getCorePoolSize(), p.getMaximumPoolSize(),
                    (int) p.getKeepAliveTime(TimeUnit.SECONDS), p.getActiveCount(),
                    p.getQueue().size(), target, lastAppliedAt.get(e.getKey())));
        }
        return out;
    }

    /** {@inheritDoc} */
    @Override
    public void reload() {
        applyAll(TunerAuditEntry.Source.MANUAL);
    }

    /** {@inheritDoc} */
    @Override
    public TunerPreset upsertPreset(TunerPreset preset) {
        preset.validate();
        store.upsertPreset(preset);
        log.info("[starsite-tuner] 预案落库: {} (core={}, max={}, keep={}s, queue={})",
                preset.getPresetName(), preset.getCoreSize(), preset.getMaxSize(),
                preset.getKeepAliveSeconds(), preset.getQueueCapacity());
        return preset;
    }

    /** {@inheritDoc} */
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

    /** {@inheritDoc} */
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
     * 求值并应用一轮（全部受管池）+ 阈值告警 + 审计 retention（V2）。
     *
     * @param source 触发源
     */
    public void applyAll(TunerAuditEntry.Source source) {
        LocalDateTime now = time.now();
        for (Map.Entry<String, ThreadPoolExecutor> e : pools.entrySet()) {
            applyPool(e.getKey(), e.getValue(), now, source);
            checkAlarms(e.getKey(), e.getValue());
        }
        purgeAuditsIfDue(now);
    }

    /**
     * 单池求值与应用（per-pool 锁内）：目标预案解析 → 审批门 → 条件应用 + 审计。
     *
     * @param poolName 池名
     * @param pool     线程池
     * @param now      当前时刻
     * @param source   触发源
     */
    private void applyPool(String poolName, ThreadPoolExecutor pool, LocalDateTime now,
                           TunerAuditEntry.Source source) {
        synchronized (applyLocks.computeIfAbsent(poolName, k -> new Object())) {
            TargetResolution resolution = resolveTarget(poolName, now, source);
            if (resolution != null) {
                doApplyPool(poolName, pool, now, source, resolution);
            }
        }
    }

    /** 目标解析结果（预案 + 实际触发源——自适应命中改写审计来源） */
    private record TargetResolution(TunerPreset preset, TunerAuditEntry.Source source) {
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
                             TunerAuditEntry.Source requestedSource, TargetResolution resolution) {
        TunerPreset preset = resolution.preset();
        TunerAuditEntry.Source source = resolution.source();
        int currentQueue = currentQueueCapacity(pool);
        int[] current = {pool.getCorePoolSize(), pool.getMaximumPoolSize(),
                (int) pool.getKeepAliveTime(TimeUnit.SECONDS), currentQueue};
        Integer wantQueue = preset.getQueueCapacity();
        int[] wanted = {preset.getCoreSize(), preset.getMaxSize(), preset.getKeepAliveSeconds(),
                wantQueue == null ? -1 : wantQueue};
        int[] last = lastApplied.get(poolName);
        if (java.util.Arrays.equals(current, wanted)
                || (last != null && java.util.Arrays.equals(last, wanted)
                        && java.util.Arrays.equals(java.util.Arrays.copyOf(current, 3),
                                java.util.Arrays.copyOf(wanted, 3)))) {
            return; // no-op 去重（账面已到位）
        }
        // 审批门（V2）：所有变更的最后一道门——DENY 即跳过（WARN 留痕，审计不记假变更）
        if (options.getApprovalHook() != null) {
            var request = new TunerChangeRequest(poolName, preset.getPresetName(), source,
                    current[0], current[1], current[2],
                    wantQueue == null ? null : currentQueue,
                    wanted[0], wanted[1], wanted[2],
                    wantQueue, host, now);
            Optional<String> deny = options.getApprovalHook().approve(request);
            if (deny.isPresent()) {
                log.warn("[starsite-tuner] 变更被审批拒绝: pool={} preset={} source={} reason={}",
                        poolName, preset.getPresetName(), source, deny.get());
                return;
            }
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
        // 队列容量（V2）：仅可变容量队列可调（instanceof 判定）；不兼容 WARN-once
        Integer oldQueue = null;
        Integer newQueue = null;
        if (wantQueue != null) {
            if (pool.getQueue() instanceof ResizableCapacityLinkedBlockingQueue<?> resizable) {
                oldQueue = resizable.getCapacity();
                newQueue = wantQueue;
                if (oldQueue != wantQueue) {
                    resizable.setCapacity(wantQueue);
                }
            } else if (queueWarned.add(poolName)) {
                log.warn("[starsite-tuner] 预案要求 queueCapacity={} 但池 {} 的队列不是 "
                                + "ResizableCapacityLinkedBlockingQueue（构造池时用 "
                                + "TunerQueues/该队列类型）——队列容量跳过",
                        wantQueue, poolName);
            }
        }
        lastApplied.put(poolName, wanted);
        lastAppliedAt.put(poolName, now);
        TunerAuditEntry audit = new TunerAuditEntry(null, now, host, poolName,
                preset.getPresetName(), source,
                current[0], current[1], current[2], wanted[0], wanted[1], wanted[2],
                oldQueue, newQueue);
        try {
            store.appendAudit(audit);
        } catch (Exception ex) {
            log.warn("[starsite-tuner] 审计落库失败（变更已生效）: {}", ex.getMessage());
        }
        log.info("[starsite-tuner] 池参数变更: pool={} preset={} source={} core {}→{} max {}→{} "
                        + "keep {}→{}s queue {}→{}",
                poolName, preset.getPresetName(), source,
                current[0], wanted[0], current[1], wanted[1], current[2], wanted[2],
                oldQueue, newQueue);
    }

    /**
     * 目标预案解析（V2 优先级）：自适应命中（冷却期内）&gt; 窗口（剩余最长者）&gt;
     * 默认绑定 &gt; 不动。
     *
     * @param poolName 池名
     * @param now      当前时刻
     * @return 目标预案
     */
    private TargetResolution resolveTarget(String poolName, LocalDateTime now,
                                           TunerAuditEntry.Source requestedSource) {
        // ① 自适应规则（冷却期内命中即生效——紧急语义压过日历；快照经 base 求值防递归）
        if (!options.getAdaptiveRules().isEmpty()) {
            LocalDateTime lastAdaptive = lastAdaptiveAt.get(poolName);
            boolean cooling = lastAdaptive != null
                    && now.isBefore(lastAdaptive.plus(options.getAdaptiveCooldown()));
            if (!cooling) {
                ThreadPoolExecutor pool = pools.get(poolName);
                if (pool != null) {
                    PoolSnapshot snap = snapshotOfBase(poolName, pool, now);
                    for (TunerAdaptiveRule rule : options.getAdaptiveRules()) {
                        try {
                            Optional<String> decision = rule.decide(snap);
                            if (decision.isPresent() && store.preset(decision.get()).isPresent()) {
                                lastAdaptiveAt.put(poolName, now);
                                log.info("[starsite-tuner] 自适应规则命中: pool={} rule={} → preset={}",
                                        poolName, rule.getClass().getSimpleName(), decision.get());
                                return store.preset(decision.get())
                                        .map(p -> new TargetResolution(p, TunerAuditEntry.Source.ADAPTIVE))
                                        .orElse(null);
                            }
                        } catch (Exception e) {
                            log.warn("[starsite-tuner] 自适应规则异常（跳过该规则）: {} - {}",
                                    rule.getClass().getSimpleName(), e.getMessage());
                        }
                    }
                }
            }
        }
        return resolveTargetBase(poolName, now)
                .map(p -> new TargetResolution(p, requestedSource))
                .orElse(null);
    }

    /**
     * 基础目标解析（窗口剩余最长者 &gt; 默认绑定；不含自适应——snapshotOf 复用防递归）。
     *
     * @param poolName 池名
     * @param now      当前时刻
     * @return 目标预案
     */
    private Optional<TunerPreset> resolveTargetBase(String poolName, LocalDateTime now) {
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
     * 阈值告警（V2）：活跃度/队列水位越限 → 钩子上抛（同池同类型冷却窗口内一次）。
     *
     * @param poolName 池名
     * @param pool     线程池
     */
    private void checkAlarms(String poolName, ThreadPoolExecutor pool) {
        TunerAlarmHook hook = options.getAlarmHook();
        if (hook == null) {
            return;
        }
        LocalDateTime now = time.now();
        // 活跃度 = 活跃 / max
        double activeRatio = pool.getMaximumPoolSize() == 0 ? 0
                : (double) pool.getActiveCount() / pool.getMaximumPoolSize();
        fireIfDue(hook, poolName, now, TunerAlarm.Type.ACTIVE_RATIO, activeRatio,
                options.getAlarmActiveRatio());
        // 队列水位 = 积压 / 容量（仅可变容量队列可评估；无界不评估）
        if (pool.getQueue() instanceof ResizableCapacityLinkedBlockingQueue<?> resizable) {
            double queueRatio = resizable.getCapacity() == 0 ? 0
                    : (double) pool.getQueue().size() / resizable.getCapacity();
            fireIfDue(hook, poolName, now, TunerAlarm.Type.QUEUE_RATIO, queueRatio,
                    options.getAlarmQueueRatio());
        }
    }

    /**
     * 单项告警（越阈值 + 冷却窗口外才上抛）。
     *
     * @param hook      告警钩子
     * @param poolName  池名
     * @param now       当前时刻
     * @param type      类型
     * @param value     当前值
     * @param threshold 阈值
     */
    private void fireIfDue(TunerAlarmHook hook, String poolName, LocalDateTime now,
                           TunerAlarm.Type type, double value, double threshold) {
        if (value < threshold) {
            return;
        }
        String key = poolName + ":" + type.name();
        LocalDateTime last = lastAlarmAt.get(key);
        if (last != null && now.isBefore(last.plus(options.getAlarmCooldown()))) {
            return; // 冷却窗口内——防告警风暴
        }
        lastAlarmAt.put(key, now);
        try {
            hook.onAlarm(new TunerAlarm(poolName, type, value, threshold));
        } catch (Exception e) {
            log.warn("[starsite-tuner] 告警钩子异常（不影响治理）: {}", e.getMessage());
        }
    }

    /**
     * 自适应规则求值（与 applyPool 分离：规则命中改写下一轮目标——本轮已应用的不再改）。
     * 空实现占位：规则在 resolveTarget 内联求值（同轮生效），此处仅保留语义锚点。
     *
     * @param poolName 池名
     * @param pool     线程池
     * @param now      当前时刻
     */
    private void checkAdaptive(String poolName, ThreadPoolExecutor pool, LocalDateTime now) {
        // 自适应求值已内联于 resolveTarget（同轮生效 + 冷却防抖）——保留方法锚点
        // 便于未来将"规则触发"与"预案应用"解耦（如规则只做告警不切预案的模式）
    }

    /**
     * 审计 retention（V2）：到期审计行两步法清理（圈 id → IN 删除，分片红线合规）；
     * 每轮 poll 只在到期窗口触发一次（间隔=轮询间隔，低频卫生）。
     *
     * @param now 当前时刻
     */
    private void purgeAuditsIfDue(LocalDateTime now) {
        if (options.getAuditRetentionDays() <= 0) {
            return; // 默认关——合规要求长保留
        }
        try {
            long removed;
            int rounds = 0;
            do {
                removed = store.purgeExpiredAudits(options.getAuditPurgeBatch(),
                        now.minusDays(options.getAuditRetentionDays()));
                rounds++;
            } while (removed >= options.getAuditPurgeBatch() && rounds < 20);
        } catch (Exception e) {
            log.warn("[starsite-tuner] 审计清理失败（下轮重试）: {}", e.getMessage());
        }
    }

    /**
     * 读池当前队列容量（可变容量队列给真实值；其他队列 -1=不管理语义）。
     *
     * @param pool 线程池
     * @return 当前队列容量；不可变队列为 -1
     */
    private int currentQueueCapacity(ThreadPoolExecutor pool) {
        return pool.getQueue() instanceof ResizableCapacityLinkedBlockingQueue<?> resizable
                ? resizable.getCapacity() : -1;
    }

    /**
     * 池快照（自适应规则入参；目标经 base 解析——不含自适应，防 snapshotOf⇄resolveTarget 递归）。
     *
     * @param poolName 池名
     * @param pool     线程池
     * @param now      当前时刻
     * @return 快照
     */
    private PoolSnapshot snapshotOfBase(String poolName, ThreadPoolExecutor pool, LocalDateTime now) {
        String target = resolveTargetBase(poolName, now)
                .map(TunerPreset::getPresetName).orElse("baseline");
        return new PoolSnapshot(poolName, pool.getCorePoolSize(), pool.getMaximumPoolSize(),
                (int) pool.getKeepAliveTime(TimeUnit.SECONDS), pool.getActiveCount(),
                pool.getQueue().size(), target, lastAppliedAt.get(poolName));
    }

    /**
     * 窗口命中判定（纯时间函数）。
     *
     * @param w   窗口
     * @param now 当前时刻
     * @return true 在窗口内
     */
    private boolean insideWindow(TunerWindow w, LocalDateTime now) {
        return fire(w.getStartCron(), now) > fire(w.getEndCron(), now);
    }

    /**
     * cron 下次触发（毫秒；解析失败按"永不触发"——治理配置错误不拖垮业务池）。
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
     * 窗口写入期校验：cron 语法（纯本地）先行、预案存在性（存储查询）在后。
     *
     * @param window 窗口
     */
    private void validateWindow(TunerWindow window) {
        if (window.getPoolName() == null || window.getPoolName().isBlank()
                || window.getPresetName() == null || window.getPresetName().isBlank()) {
            throw new IllegalArgumentException("窗口的池名与预案名不可为空");
        }
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

    /** hostname:pid（观测留痕，与家族组件同形） */
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
