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
package com.xp.starsite.sequence;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 雪花 id 生成器（64 位 = 1 符号位 + 时间戳 + 机器位 + 序列位，三段位宽可配）。
 * <p>
 * 相对常见实现的两个生产强化（对齐《分布式 ID：从雪花算法到生产实现》）：
 * <ul>
 *   <li><b>时钟回拨钳制（借序列位）</b>：回拨发生时把虚拟时间冻结在上次发号毫秒，
 *       用序列位余量继续发号，等物理时钟自己追平——id 时间戳字段绝不倒退。
 *       回拨超过 {@code maxBackwardMillis} 时按策略处理：{@link BackwardPolicy#FAIL} 抛
 *       {@link ClockBackwardException} 拒绝发号；{@link BackwardPolicy#CLAMP} 继续钳制（告警一次）。</li>
 *   <li><b>可注入时间源</b>：{@link TimeSource} 抽象，测试可确定性构造回拨场景。</li>
 * </ul>
 * 线程安全：单锁串行发号（发号本身纳秒级，锁竞争在 409.6w/s 理论上限内不构成瓶颈）。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class SnowflakeIdGenerator {

    /**
     * 时钟回拨处理策略：回拨幅度超过 {@code maxBackwardMillis} 容忍上限时按此策略处置。
     */
    public enum BackwardPolicy {
        /** 钳制 + 借序列位：任何幅度都冻结虚拟时间继续发号，可用性优先（大回拨告警一次） */
        CLAMP,
        /** 超过容忍上限抛 {@link ClockBackwardException} 拒绝发号，时间戳单调性优先 */
        FAIL
    }

    /** 组件日志器，仅用于 CLAMP 大回拨告警 */
    private static final Logger log = LoggerFactory.getLogger(SnowflakeIdGenerator.class);

    /** 起始纪元毫秒：id 时间戳段 = 当前毫秒 - epoch，决定 id 数值起点与可用年限 */
    private final long epoch;

    /** 机器位：本实例在平台内的唯一标识，写入 id 的机器位段 */
    private final long workerId;

    /** 机器位宽度（位），决定 workerId 上限 2^workerIdBits - 1 */
    private final int workerIdBits;

    /** 序列位宽度（位），决定单毫秒发号上限 2^sequenceBits */
    private final int sequenceBits;

    /** 序列位掩码（低 sequenceBits 位全 1），序列自增后与之按位与实现回绕取余 */
    private final long sequenceMask;

    /** 机器位左移位数 = sequenceBits，拼接 id 时定位机器位段 */
    private final long workerShift;

    /** 时间戳段左移位数 = workerIdBits + sequenceBits，拼接 id 时定位时间戳段 */
    private final long timestampShift;

    /** 时钟回拨容忍上限（毫秒）：回拨不超该值一律钳制，超过则按 backwardPolicy 处置 */
    private final long maxBackwardMillis;

    /** 回拨超过容忍上限时的处置策略（CLAMP 继续钳制 / FAIL 抛异常） */
    private final BackwardPolicy backwardPolicy;

    /** 时间源：生产为系统时钟，测试注入假时钟 */
    private final TimeSource time;

    /** 发号互斥锁：串行化"读时钟-推序列-拼 id"临界区，保证同毫秒内序列不重号 */
    private final Lock lock = new ReentrantLock();

    /** 当前毫秒内已发出的序列号：翻毫秒重置为 0，自增到掩码回绕为 0（表示耗尽） */
    private long sequence = 0L;

    /** 上次发号的（虚拟）毫秒：钳制时的冻结基准；-1 表示尚未发过号 */
    private long lastTimestamp = -1L;

    /** CLAMP 大回拨是否已告警过（只告警一次防日志刷屏） */
    private boolean warnedBigBackward = false;

    /**
     * 使用系统时钟构造生成器（生产入口）。
     *
     * @param workerId          机器位，必须在 0 ~ 2^workerIdBits - 1 范围内
     * @param epoch             起始纪元毫秒（id 时间戳基准），应取过去的固定时刻
     * @param workerIdBits      机器位宽度（位），与 sequenceBits 之和不能超过 22
     * @param sequenceBits      序列位宽度（位），决定单毫秒发号上限
     * @param maxBackwardMillis 时钟回拨容忍上限毫秒，回拨超过该值按 backwardPolicy 处置
     * @param backwardPolicy    回拨超限策略：CLAMP 钳制继续发号 / FAIL 抛异常拒绝
     * @throws IllegalArgumentException workerId 为负或超过位宽上限，或 workerIdBits + sequenceBits 超过 22
     */
    public SnowflakeIdGenerator(long workerId, long epoch, int workerIdBits, int sequenceBits,
                                long maxBackwardMillis, BackwardPolicy backwardPolicy) {
        this(workerId, epoch, workerIdBits, sequenceBits, maxBackwardMillis, backwardPolicy, TimeSource.system());
    }

    /**
     * 构造生成器并显式指定时间源（测试入口：注入假时钟构造回拨/同毫秒场景）。
     *
     * @param workerId          机器位，必须在 0 ~ 2^workerIdBits - 1 范围内
     * @param epoch             起始纪元毫秒（id 时间戳基准），应取过去的固定时刻
     * @param workerIdBits      机器位宽度（位），与 sequenceBits 之和不能超过 22
     * @param sequenceBits      序列位宽度（位），决定单毫秒发号上限
     * @param maxBackwardMillis 时钟回拨容忍上限毫秒，回拨超过该值按 backwardPolicy 处置
     * @param backwardPolicy    回拨超限策略：CLAMP 钳制继续发号 / FAIL 抛异常拒绝
     * @param time              时间源，生产传 {@link TimeSource#system()}，测试传假时钟实现
     * @throws IllegalArgumentException workerId 为负或超过位宽上限，或 workerIdBits + sequenceBits 超过 22
     */
    public SnowflakeIdGenerator(long workerId, long epoch, int workerIdBits, int sequenceBits,
                                long maxBackwardMillis, BackwardPolicy backwardPolicy, TimeSource time) {
        long maxWorkerId = ~(-1L << workerIdBits);
        if (workerId < 0 || workerId > maxWorkerId) {
            throw new IllegalArgumentException(
                    "workerId 必须在 0~%d 之间（%d 位机器位）: %d".formatted(maxWorkerId, workerIdBits, workerId));
        }
        if (workerIdBits + sequenceBits > 22) {
            throw new IllegalArgumentException("workerIdBits + sequenceBits 不能超过 22（时间戳至少保留 41 位）");
        }
        this.workerId = workerId;
        this.epoch = epoch;
        this.workerIdBits = workerIdBits;
        this.sequenceBits = sequenceBits;
        this.sequenceMask = ~(-1L << sequenceBits);
        this.workerShift = sequenceBits;
        this.timestampShift = workerIdBits + sequenceBits;
        this.maxBackwardMillis = maxBackwardMillis;
        this.backwardPolicy = backwardPolicy;
        this.time = time;
    }

    /**
     * 生成下一个全局唯一 id。
     * <p>
     * 行为：同一毫秒内序列位递增；翻毫秒序列重置为 0；物理时钟回拨时把虚拟时间
     * 钳制在上次发号毫秒、借序列位余量继续发号（时间戳字段绝不倒退）；
     * 序列耗尽则自旋等待物理时间越过当前毫秒再继续。
     *
     * @return 64 位雪花 id = ((timestamp - epoch) &lt;&lt; timestampShift) | (workerId &lt;&lt; workerShift) | sequence，
     *         同一生成器实例内严格递增
     * @throws ClockBackwardException 回拨幅度超过 maxBackwardMillis 且策略为 {@link BackwardPolicy#FAIL} 时抛出，
     *                                抛出即本次不发号
     */
    public long nextId() {
        lock.lock();
        try {
            long timestamp = time.currentTimeMillis();
            if (timestamp < lastTimestamp) {
                long backward = lastTimestamp - timestamp;
                if (backwardPolicy == BackwardPolicy.FAIL && backward > maxBackwardMillis) {
                    throw new ClockBackwardException(backward, maxBackwardMillis);
                }
                if (backward > maxBackwardMillis && !warnedBigBackward) {
                    // CLAMP 模式的大回拨：序列位余量兜底唯一性，但必须可见（只告警一次防刷屏）
                    warnedBigBackward = true;
                    log.warn("[starsite-sequence] 时钟回拨 {} ms 超过容忍 {} ms，按 CLAMP 策略钳制虚拟时间继续发号"
                            + "（序列余量耗尽时将自旋等待物理时钟追平）", backward, maxBackwardMillis);
                }
                // 钳制：虚拟时间冻结在上次发号毫秒，借序列余量（时间戳字段不倒退）
                timestamp = lastTimestamp;
            }
            if (timestamp == lastTimestamp) {
                sequence = (sequence + 1) & sequenceMask;
                if (sequence == 0) {
                    // 序列耗尽：自旋等物理时间越过 lastTimestamp（被钳制时即等真实时钟追平）
                    while ((timestamp = time.currentTimeMillis()) <= lastTimestamp) {
                        Thread.onSpinWait();
                    }
                }
            } else {
                sequence = 0L;
            }
            lastTimestamp = timestamp;
            return ((timestamp - epoch) << timestampShift) | (workerId << workerShift) | sequence;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 生成下一个全局唯一 id 的字符串形态：前端 JS Number 只有 53 位精度，交互场景统一用字符串防丢精度。
     *
     * @return 无符号十进制字符串形态的雪花 id
     * @throws ClockBackwardException 抛出条件同 {@link #nextId()}
     */
    public String nextIdStr() {
        return Long.toUnsignedString(nextId());
    }

    /**
     * 获取机器位。
     *
     * @return 构造时传入的机器位
     */
    public long getWorkerId() {
        return workerId;
    }

    /**
     * 获取机器位宽度。
     *
     * @return 机器位宽度（位）
     */
    public int getWorkerIdBits() {
        return workerIdBits;
    }

    /**
     * 获取序列位宽度。
     *
     * @return 序列位宽度（位）
     */
    public int getSequenceBits() {
        return sequenceBits;
    }
}
