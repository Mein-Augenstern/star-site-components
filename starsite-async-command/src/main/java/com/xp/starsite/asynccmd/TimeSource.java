package com.xp.starsite.asynccmd;

/**
 * 时间源抽象（async-command 模块自持，与 sequence 模块解耦）。
 * 生产用 {@link #system()}；测试注入假时钟确定性构造延迟/超时场景。
 *
 * @author muyi
 * @since 2026-10
 */
@FunctionalInterface
public interface TimeSource {

    java.time.LocalDateTime now();

    static TimeSource system() {
        return java.time.LocalDateTime::now;
    }
}
