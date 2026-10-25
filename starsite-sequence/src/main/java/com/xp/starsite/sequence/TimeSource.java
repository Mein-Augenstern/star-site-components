package com.xp.starsite.sequence;

/**
 * 时间源抽象：为雪花生成器提供可注入的当前毫秒。
 * 生产用 {@link #system()}；测试注入假时钟以确定性构造时钟回拨/同毫秒场景。
 *
 * @author muyi
 * @since 2026-10
 */
@FunctionalInterface
public interface TimeSource {

    long currentTimeMillis();

    static TimeSource system() {
        return System::currentTimeMillis;
    }
}
