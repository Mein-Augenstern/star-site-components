package com.xp.starsite.asynccmd;

import org.springframework.context.SmartLifecycle;

/**
 * 调度器的 Spring 生命周期适配（starter 粘合层）：phase 取最大值——
 * 容器中最后启动、最先停止，尽量不丢尾批任务。core 的调度器保持零 Spring 依赖。
 *
 * @author muyi
 * @since 2026-10
 */
public class AsyncCommandLifecycle implements SmartLifecycle {

    private final AsyncCommandScheduler scheduler;

    public AsyncCommandLifecycle(AsyncCommandScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public void start() {
        scheduler.start();
    }

    @Override
    public void stop() {
        scheduler.stop();
    }

    @Override
    public boolean isRunning() {
        return scheduler.isStarted();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
