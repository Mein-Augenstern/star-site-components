package com.xp.starsite.sequence;

/**
 * 时钟回拨超限异常：回拨幅度超过 {@code maxBackwardMillis} 且策略为 FAIL 时抛出。
 * 抛出即拒绝发号——宁可短暂不可用，绝不发出时间戳倒退的 id。
 *
 * @author muyi
 * @since 2026-10
 */
public class ClockBackwardException extends RuntimeException {

    /** 观测到的回拨毫秒数 */
    private final long backwardMillis;

    /** 配置容忍上限毫秒数 */
    private final long maxBackwardMillis;

    public ClockBackwardException(long backwardMillis, long maxBackwardMillis) {
        super("时钟回拨 %d ms 超过容忍上限 %d ms，按策略拒绝发号".formatted(backwardMillis, maxBackwardMillis));
        this.backwardMillis = backwardMillis;
        this.maxBackwardMillis = maxBackwardMillis;
    }

    public long getBackwardMillis() {
        return backwardMillis;
    }

    public long getMaxBackwardMillis() {
        return maxBackwardMillis;
    }
}
