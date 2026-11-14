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
package com.xp.starsite.idempotent.core;

import com.xp.starsite.idempotent.api.IdempotentAction;
import com.xp.starsite.idempotent.api.IdempotentClient;
import com.xp.starsite.idempotent.api.IdempotentRecord;
import com.xp.starsite.idempotent.api.IdempotentResult;
import com.xp.starsite.idempotent.clock.TimeSource;
import com.xp.starsite.idempotent.store.ClaimOutcome;
import com.xp.starsite.idempotent.store.IdempotentStore;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 幂等引擎（{@link IdempotentClient} 门面实现）：认领 → 读行 → 回放/等待/回收 的
 * 四态循环。正确性全部落在存储端原子操作（uk INSERT / 条件 UPDATE），
 * 引擎只做决策编排——两实例同键并发时恰一执行是存储互斥的直接结果。
 *
 * <pre>
 * tryClaim(uk INSERT) ──认领──► 执行动作 ──成功──► complete(DONE+result) → EXECUTED
 *        │未认领                      └─异常─► fail(FAILED) → 重抛（键释放）
 *        ▼
 *     读行判定：
 *       DONE 未过期   → 回放 result（REPLAYED，动作零执行）
 *       PROCESSING   → 在飞：等待窗口内轮询（完成→回放 / 失败→FAILED 摘要 / 超时→CONFLICT）
 *       FAILED       → 新请求回收重执行；等待目睹失败 → FAILED 摘要（不自动重跑——防重试风暴）
 *       过期/停滞     → tryReclaim（原子）→ 认领成功者执行
 * </pre>
 *
 * <p><b>等待期间目睹失败不重跑</b>是刻意语义：失败动作的并发重试风暴比单次失败更危险；
 * 客户端显式重试（新请求）才走回收重执行。
 *
 * @author muyi
 * @date 2026/11/12
 */
public class IdempotentEngine implements IdempotentClient {

    private static final Logger log = LoggerFactory.getLogger(IdempotentEngine.class);

    /** 键记忆窗口（Stripe 同款 24h 缺省；DONE 过期即"键遗忘"重新执行） */
    private final Duration keyTtl;

    /** 在飞等待窗口（超时即 CONFLICT——web 侧映射 409） */
    private final Duration waitTimeout;

    /** 等待轮询间隔（lock 组件限时等待同款 100ms 节奏） */
    private final long pollMillis;

    /** PROCESSING 停滞判崩溃遗留的阈值（应大于业务动作最长执行时长——同锁 TTL 语义） */
    private final Duration processingStale;

    /** 幂等存储（互斥与状态的唯一事实源——D4 存储端铁律） */
    private final IdempotentStore store;

    /** 时间源（窗口/停滞判定与测试假时钟） */
    private final TimeSource time;

    /** 本实例标识（hostname:pid，认领留痕） */
    private final String host;

    /** 循环安全上限（认领与清理的删除竞态重试——正常 1~2 轮即决出，超限属异常态） */
    private static final int MAX_ATTEMPTS = 16;

    /** key 列宽上限（表 idem_key VARCHAR(128)，超长 fail-fast 防静默截断撞键） */
    private static final int KEY_MAX = 128;

    /** 失败摘要截断上限（error 列 VARCHAR(500)，对齐 async-command last_error 口径） */
    private static final int ERROR_MAX = 500;

    /**
     * 构造。
     *
     * @param store          幂等存储
     * @param time           时间源
     * @param keyTtl         键记忆窗口（建议 ≥ 业务可容忍的重复提交跨度）
     * @param waitTimeout    在飞等待窗口（0 = 不等待，在飞即 CONFLICT）
     * @param pollMillis     等待轮询间隔毫秒（&gt;=1）
     * @param processingStale PROCESSING 停滞阈值（应大于动作最长执行时长）
     */
    public IdempotentEngine(IdempotentStore store, TimeSource time, Duration keyTtl,
                            Duration waitTimeout, long pollMillis, Duration processingStale) {
        this.store = store;
        this.time = time;
        this.keyTtl = keyTtl;
        this.waitTimeout = waitTimeout;
        this.pollMillis = Math.max(1, pollMillis);
        this.processingStale = processingStale;
        this.host = resolveHost();
    }

    /** {@inheritDoc} */
    @Override
    public IdempotentResult guard(String scope, String key, IdempotentAction action) {
        String normalizedScope = scope == null ? "" : scope.trim();
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("幂等键不可为空（scope=" + normalizedScope + "）");
        }
        String normalizedKey = key.trim();
        if (normalizedKey.length() > KEY_MAX) {
            throw new IllegalArgumentException("幂等键超长（" + normalizedKey.length()
                    + " > " + KEY_MAX + "，表列宽上限——改短键或存摘要）: " + prefix(normalizedKey));
        }
        // 在飞等待窗口走单调钟（nanoTime）——TimeSource 只承载语义时间戳（expire_at/gmt），
        // 等待计时用真实流逝（假时钟测试冻结语义时间不影响等待窗口推进，与 schedule
        // 引擎时长计算同哲学）
        long deadlineNanos = System.nanoTime() + waitTimeout.toNanos();
        boolean sawInFlight = false;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            LocalDateTime now = time.now();
            ClaimOutcome claim = store.tryClaim(normalizedScope, normalizedKey,
                    windowEnd(now), host);
            if (claim.claimed()) {
                return runAction(claim.record(), action);
            }
            Optional<IdempotentRecord> row = store.find(normalizedScope, normalizedKey);
            if (row.isEmpty()) {
                // 与 retention 清理的竞态（判定前刚被删）——键空闲，回到认领
                continue;
            }
            IdempotentRecord record = row.get();
            now = time.now();
            switch (record.getStatus()) {
                case DONE:
                    if (now.isBefore(record.getExpireAt())) {
                        log.debug("[starsite-idempotent] 回放 {}#{}（首次结果命中）",
                                normalizedScope, prefix(normalizedKey));
                        return IdempotentResult.replayed(record.getResult());
                    }
                    break; // 键遗忘 → 走回收
                case FAILED:
                    if (sawInFlight) {
                        // 等待期间目睹他方失败：返回摘要不重跑（显式重试才回收）
                        return IdempotentResult.failed(record.getError());
                    }
                    break; // 失败后的新请求 → 键已释放，走回收重执行
                case PROCESSING:
                default:
                    LocalDateTime staleBefore = now.minus(processingStale);
                    if (record.getGmtModified() == null
                            || record.getGmtModified().isBefore(staleBefore)) {
                        break; // 崩溃遗留 → 走回收
                    }
                    sawInFlight = true;
                    if (System.nanoTime() >= deadlineNanos) {
                        log.debug("[starsite-idempotent] {}#{} 在飞等待窗口耗尽 → CONFLICT",
                                normalizedScope, prefix(normalizedKey));
                        return IdempotentResult.conflict();
                    }
                    if (!sleepPoll()) {
                        // 中断：恢复标记 + 按窗口耗尽处理（lock 限时等待同语义）
                        return IdempotentResult.conflict();
                    }
                    continue;
            }
            // 回收路径（FAILED 新请求 / 过期 DONE / 停滞 PROCESSING）——原子决出认领者
            LocalDateTime reclaimAt = time.now();
            Optional<IdempotentRecord> reclaimed = store.tryReclaim(normalizedScope,
                    normalizedKey, windowEnd(reclaimAt), reclaimAt,
                    reclaimAt.minus(processingStale), host);
            if (reclaimed.isPresent()) {
                log.info("[starsite-idempotent] 回收 {}#{} 再执行（前态 {}）", normalizedScope,
                        prefix(normalizedKey), record.getStatus());
                return runAction(reclaimed.get(), action);
            }
            // 回收未中 = 他方先一步回收（在飞）→ 回到读行等待
        }
        throw new IllegalStateException("幂等认领循环未决（" + MAX_ATTEMPTS + " 轮——"
                + "retention 清理与认领的持续竞态属异常态，检查清理批量/键分布）: "
                + normalizedScope + "#" + prefix(normalizedKey));
    }

    /** {@inheritDoc} */
    @Override
    public long purgeExpired(int batchSize) {
        return store.purgeExpired(batchSize, time.now());
    }

    /**
     * 获取本实例标识。
     *
     * @return hostname:pid
     */
    public String getHost() {
        return host;
    }

    /**
     * 运行动作并落终态：成功 complete（守卫失败即结果弃写 WARN——动作超 keyTtl 被回收），
     * 失败 fail 后重抛（RuntimeException/Error 原样，受检包装 IllegalStateException）。
     *
     * @param record 认领/回收所得的 PROCESSING 行
     * @param action 业务动作
     * @return EXECUTED 结局
     */
    private IdempotentResult runAction(IdempotentRecord record, IdempotentAction action) {
        try {
            String payload = action.run();
            if (!store.complete(record.getScope(), record.getIdemKey(),
                    record.getExpireAt(), payload)) {
                // 行已被回收（动作执行超窗且停滞判死）——旧代终态弃写（双条件守卫拦截）
                log.warn("[starsite-idempotent] {}#{} 终态弃写（执行超窗被回收——检查 key-ttl"
                        + " 与 processing-stale 是否大于动作最长耗时）",
                        record.getScope(), prefix(record.getIdemKey()));
            }
            return IdempotentResult.executed(payload);
        } catch (Throwable t) {
            try {
                store.fail(record.getScope(), record.getIdemKey(),
                        record.getExpireAt(), summarize(t));
            } catch (Exception markFail) {
                log.warn("[starsite-idempotent] {}#{} 失败标记未落（{}）", record.getScope(),
                        prefix(record.getIdemKey()), markFail.getMessage());
            }
            if (t instanceof RuntimeException re) {
                throw re;
            }
            if (t instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException("幂等动作执行失败（受检异常包装）", t);
        }
    }

    /**
     * 等待一轮。
     *
     * @return false 被中断（已恢复中断标记——调用方按窗口耗尽处理）
     */
    private boolean sleepPoll() {
        try {
            Thread.sleep(pollMillis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 键记忆窗口边界：now + keyTtl <b>归整到秒</b>——代际令牌的等值比对要求引擎持有值
     * 与存储落库值完全一致；MySQL DATETIME 默认秒精度会把纳秒截断（demo 实证：complete
     * 的 expire_at 等值条件永不命中，终态落不下去）。归整到秒后三种存储形态（DATETIME/
     * 定长 ISO/memory）令牌一致。同秒重复代际不可能：回收距上次认领至少 processing-stale
     * （默认 120s），两代窗口边界必差整分钟级。
     *
     * @param now 当前时刻
     * @return 归整到秒的窗口边界
     */
    private LocalDateTime windowEnd(LocalDateTime now) {
        return now.plus(keyTtl).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    }

    /** 失败摘要：类型+消息，截断 500 */
    private static String summarize(Throwable t) {
        String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
        return msg.length() > ERROR_MAX ? msg.substring(0, ERROR_MAX) : msg;
    }

    /** 键脱敏前缀（日志防刷屏——键可能含业务敏感串） */
    private static String prefix(String key) {
        return key.length() <= 16 ? key : key.substring(0, 16) + "…";
    }

    /** hostname:pid（hostname 取不到退 localhost——标识仅用于观测留痕，与 schedule 同形） */
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
