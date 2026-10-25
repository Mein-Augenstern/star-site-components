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
package com.xp.starsite.asynccmd.client;

import com.xp.starsite.asynccmd.clock.TimeSource;
import com.xp.starsite.asynccmd.config.AsyncCommandConfig;
import com.xp.starsite.asynccmd.metrics.AsyncCommandMetrics;
import com.xp.starsite.asynccmd.model.AsyncCommand;
import com.xp.starsite.asynccmd.store.AsyncCommandStore;
import java.time.LocalDateTime;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 异步命令提交入口：在业务事务内调用 submit，命令与业务变更同事务原子落库
 * （表形态的立身之本），提交方只管写，调度器负责可靠执行与重试。
 * 同时承担调度器一侧的状态回写（成功/重试/死信）。
 * <p>
 * IMMEDIATE 模式联动：落库成功后回调 {@code submitHook}（由装配层回填为调度器的
 * 即时派发入口），实现"提交即执行"；回调异常被吞掉并降级为等下一轮调度（可靠性不降级）。
 * <p>
 * 线程安全性：除 volatile 的 submitHook 外无可变状态，方法可多线程并发调用；
 * 可靠性依赖底层 store 实现的线程安全。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class AsyncCommandClient {

    /** 日志（slf4j） */
    private static final Logger log = LoggerFactory.getLogger(AsyncCommandClient.class);

    /** 命令存储（提交与状态回写的落地通道） */
    private final AsyncCommandStore store;
    /** 运行参数（默认重试次数/退避/提交方标识） */
    private final AsyncCommandConfig config;
    /** 时间源（nextRunAt 计算基准；测试可注入假时钟） */
    private final TimeSource time;
    /** 指标出口：提交/重试/死信计数（未装配监控时为 NOOP，零开销） */
    private final AsyncCommandMetrics metrics;

    /**
     * 提交钩子（IMMEDIATE 模式派发入口）：落库成功后回调，调度器据此立即认领并投池；
     * volatile——由装配层在两个 Bean 就绪后 set，业务线程与调度线程都需要可见。
     * 为 null（core 纯手工形态）时 IMMEDIATE 退化为"落库等下一轮调度"（可靠性不降级）。
     */
    private volatile Consumer<AsyncCommand> submitHook;

    /**
     * 便捷构造（时间源取系统时钟，指标取 NOOP）。
     *
     * @param store  命令存储
     * @param config 运行参数
     */
    public AsyncCommandClient(AsyncCommandStore store, AsyncCommandConfig config) {
        this(store, config, TimeSource.system(), AsyncCommandMetrics.NOOP);
    }

    /**
     * 构造（指标取 NOOP）。
     *
     * @param store  命令存储
     * @param config 运行参数
     * @param time   时间源（测试注入假时钟用）
     */
    public AsyncCommandClient(AsyncCommandStore store, AsyncCommandConfig config, TimeSource time) {
        this(store, config, time, AsyncCommandMetrics.NOOP);
    }

    /**
     * 全参构造。
     *
     * @param store   命令存储
     * @param config  运行参数
     * @param time    时间源（测试注入假时钟用）
     * @param metrics 指标出口（提交/重试/死信计数；无监控传 {@link AsyncCommandMetrics#NOOP}）
     */
    public AsyncCommandClient(AsyncCommandStore store, AsyncCommandConfig config,
                              TimeSource time, AsyncCommandMetrics metrics) {
        this.store = store;
        this.config = config;
        this.time = time;
        this.metrics = metrics;
    }

    /**
     * 提交立即执行的命令：到期时间取当前时间，下一轮调度即认领；
     * 执行器 mode 为 IMMEDIATE 时经提交钩子立即派发（不等轮询）。
     *
     * @param cmdType     命令类型（执行器路由键，须与某个执行器 type() 一致，否则按无执行器重试直至 DEAD）
     * @param bizKey      业务键（幂等去重/追踪用；null 归一为空串）
     * @param payloadJson 载荷 JSON 字符串（结构由执行器约定；允许 null）
     * @return 已落库命令（id 已由存储回填，status = PENDING）
     */
    public AsyncCommand submit(String cmdType, String bizKey, String payloadJson) {
        return submitAt(cmdType, bizKey, payloadJson, time.now());
    }

    /**
     * 提交延迟执行的命令（延迟队列场景，如"30 分钟未支付取消订单"）：到期前不会被认领。
     * 注意：延迟命令与 IMMEDIATE 模式组合时，未到期不会被即时派发（钩子按到期判定）。
     *
     * @param cmdType     命令类型（执行器路由键）
     * @param bizKey      业务键（幂等去重/追踪用；null 归一为空串）
     * @param payloadJson 载荷 JSON 字符串（结构由执行器约定；允许 null）
     * @param runAt       首次执行到期时间（now = 立即执行；未来时刻 = 延迟执行）
     * @return 已落库命令（id 已由存储回填，status = PENDING）
     */
    public AsyncCommand submitAt(String cmdType, String bizKey, String payloadJson, LocalDateTime runAt) {
        AsyncCommand cmd = new AsyncCommand();
        cmd.setCmdType(cmdType);
        cmd.setBizKey(bizKey == null ? "" : bizKey);
        cmd.setPayload(payloadJson);
        cmd.setStatus(AsyncCommand.PENDING);
        cmd.setMaxRetry(config.getDefaultMaxRetry());
        cmd.setRetryCount(0);
        cmd.setNextRunAt(runAt);
        cmd.setOwner(config.getOwner());
        store.insert(cmd);
        metrics.recordSubmitted(cmdType);
        log.info("async cmd submitted: type={}, key={}, id={}, runAt={}", cmdType, bizKey, cmd.getId(), runAt);
        if (submitHook != null) {
            // IMMEDIATE 模式的即时派发；钩子内部自行容错，异常不影响提交语义
            try {
                submitHook.accept(cmd);
            } catch (RuntimeException e) {
                log.warn("async cmd submit-hook error (fallback to scheduled): id={}, err={}",
                        cmd.getId(), e.getMessage());
            }
        }
        return cmd;
    }

    /**
     * 幂等提交：同 type+bizKey 已有未完成（PENDING/RUNNING）命令时跳过落库（防重复触发，如定时任务反复提交）。
     * 注意是"检查 + 提交"两步，非原子——极端并发下仍可能各落一条，需严格幂等时由调用方加分布式锁。
     *
     * @param cmdType     命令类型（执行器路由键）
     * @param bizKey      业务键（幂等去重锚点；null 归一为空串）
     * @param payloadJson 载荷 JSON 字符串（允许 null）
     * @return 已存在未完成命令时返回 null（跳过提交）；否则返回新落库命令
     */
    public AsyncCommand submitIdempotent(String cmdType, String bizKey, String payloadJson) {
        if (store.countUnfinished(cmdType, bizKey == null ? "" : bizKey) > 0) {
            return null;
        }
        return submit(cmdType, bizKey, payloadJson);
    }

    /**
     * 按 id 取命令（执行器回显/排查用）。
     *
     * @param id 命令主键
     * @return 命令；不存在返回 null
     */
    public AsyncCommand findById(long id) {
        return store.findById(id);
    }

    /**
     * 处理成功回写：置 SUCCESS 并清认领令牌（仅回写状态字段，retryCount 等保持原值）。
     *
     * @param cmd 刚执行成功的命令（当前持有令牌）
     */
    public void markSuccess(AsyncCommand cmd) {
        cmd.setStatus(AsyncCommand.SUCCESS);
        cmd.setClaimToken(null);
        store.updateState(cmd);
        log.debug("async cmd done: id={}, type={}", cmd.getId(), cmd.getCmdType());
    }

    /**
     * 失败回写：按指数退避重新入队，重试超限则置 DEAD（死信）。
     * <p>
     * 规则：retryCount 已达 maxRetry（命令未设置时取配置默认值）→ 置 DEAD 并记 lastError，不再调度；
     * 否则 retryCount+1、状态回 PENDING，nextRunAt = now + min(backoffBaseSeconds * 2^n, backoffMaxSeconds) 秒
     * （n 为本次失败前的重试次数，首次失败 n=0；位移封顶 16 防溢出）。两种出口都清空认领令牌并截断记录失败信息。
     *
     * @param cmd   执行失败的命令（当前持有令牌）
     * @param cause 失败原因异常；允许 null（按空串记录）
     */
    public void markRetryOrFail(AsyncCommand cmd, Exception cause) {
        int retry = cmd.getRetryCount() == null ? 0 : cmd.getRetryCount();
        String msg = cause == null ? "" : String.valueOf(cause.getMessage());
        cmd.setClaimToken(null);
        if (retry >= (cmd.getMaxRetry() == null ? config.getDefaultMaxRetry() : cmd.getMaxRetry())) {
            cmd.setStatus(AsyncCommand.DEAD);
            cmd.setLastError(truncate(msg));
            store.updateState(cmd);
            metrics.recordDead(cmd.getCmdType());
            log.error("async cmd DEAD: id={}, type={}, lastError={}", cmd.getId(), cmd.getCmdType(), msg);
            return;
        }
        cmd.setRetryCount(retry + 1);
        long backoffSeconds = Math.min(
                config.getBackoffBaseSeconds() * (1L << Math.min(retry, 16)),
                config.getBackoffMaxSeconds());
        cmd.setStatus(AsyncCommand.PENDING);
        cmd.setNextRunAt(time.now().plusSeconds(backoffSeconds));
        cmd.setLastError(truncate(msg));
        store.updateState(cmd);
        metrics.recordRetry(cmd.getCmdType());
        log.warn("async cmd retry #{}: id={}, type={}, backoff={}s, err={}",
                retry + 1, cmd.getId(), cmd.getCmdType(), backoffSeconds, msg);
    }

    /**
     * 截断失败信息：超过 480 字符截断——last_error 列宽 VARCHAR(500)，为多字节字符留余量。
     *
     * @param s 原始字符串
     * @return 截断后的字符串；入参为 null 时返回 null
     */
    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > 480 ? s.substring(0, 480) : s;
    }

    /**
     * 设置提交钩子（装配层在调度器就绪后回填；重复设置后者覆盖）。
     *
     * @param hook 提交后回调（通常为调度器的 IMMEDIATE 派发入口；null 清除）
     */
    public void setSubmitHook(Consumer<AsyncCommand> hook) {
        this.submitHook = hook;
    }
}
