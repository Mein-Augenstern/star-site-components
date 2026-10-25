package com.xp.starsite.sequence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

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
 * @since 2026-10
 */
public class SnowflakeIdGenerator {

    /** 回拨处理策略 */
    public enum BackwardPolicy {
        /** 钳制 + 借序列位：任何幅度都冻结虚拟时间继续发号（大回拨告警一次） */
        CLAMP,
        /** 超过容忍上限抛异常拒绝发号（对时间戳单调性有强要求的场景） */
        FAIL
    }

    private static final Logger log = LoggerFactory.getLogger(SnowflakeIdGenerator.class);

    private final long epoch;
    private final long workerId;
    private final int workerIdBits;
    private final int sequenceBits;

    private final long sequenceMask;
    private final long workerShift;
    private final long timestampShift;
    private final long maxBackwardMillis;
    private final BackwardPolicy backwardPolicy;
    private final TimeSource time;

    private final Lock lock = new ReentrantLock();

    private long sequence = 0L;
    private long lastTimestamp = -1L;
    private boolean warnedBigBackward = false;

    public SnowflakeIdGenerator(long workerId, long epoch, int workerIdBits, int sequenceBits,
                                long maxBackwardMillis, BackwardPolicy backwardPolicy) {
        this(workerId, epoch, workerIdBits, sequenceBits, maxBackwardMillis, backwardPolicy, TimeSource.system());
    }

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

    /** 生成下一个全局唯一 id（同一毫秒内序列递增，翻毫秒重置；时钟回拨按策略钳制或拒绝） */
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

    /** 字符串形态（前端长精度场景） */
    public String nextIdStr() {
        return Long.toUnsignedString(nextId());
    }

    public long getWorkerId() {
        return workerId;
    }

    public int getWorkerIdBits() {
        return workerIdBits;
    }

    public int getSequenceBits() {
        return sequenceBits;
    }
}
