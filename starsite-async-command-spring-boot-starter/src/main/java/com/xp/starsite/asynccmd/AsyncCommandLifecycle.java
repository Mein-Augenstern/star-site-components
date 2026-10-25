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
package com.xp.starsite.asynccmd;

import com.xp.starsite.asynccmd.scheduler.AsyncCommandScheduler;
import org.springframework.context.SmartLifecycle;

/**
 * 调度器的 Spring 生命周期适配（starter 粘合层）：phase 取最大值——
 * 容器中最后启动、最先停止，尽量不丢尾批任务。core 的调度器保持零 Spring 依赖。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class AsyncCommandLifecycle implements SmartLifecycle {

    /** 被托管的 core 调度器（零 Spring 依赖，本类只做生命周期桥接） */
    private final AsyncCommandScheduler scheduler;

    /**
     * 构造生命周期适配器。
     *
     * @param scheduler 被托管的调度器（start/stop/isRunning 全部委托给它）
     */
    public AsyncCommandLifecycle(AsyncCommandScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * 容器启动回调（SmartLifecycle）。
     * <p>phase 设计意图：本 Bean phase 为 {@link Integer#MAX_VALUE}，排在容器中所有生命周期 Bean
     * 之后最后启动——确保其余 Bean（含执行器）全部就绪后才开始认领与执行到期命令。
     */
    @Override
    public void start() {
        scheduler.start();
    }

    /**
     * 容器停止回调（SmartLifecycle）。
     * <p>phase 设计意图：phase 最大故最先停止——容器关闭时优先停调度器，尽量完成已认领的
     * 尾批任务、减少在途命令被硬中断，然后才轮到其余 Bean 销毁。
     */
    @Override
    public void stop() {
        scheduler.stop();
    }

    /**
     * 是否处于运行态。
     *
     * @return 调度器已启动返回 true；未启动或已停止返回 false
     */
    @Override
    public boolean isRunning() {
        return scheduler.isStarted();
    }

    /**
     * 生命周期相位。
     *
     * @return Integer.MAX_VALUE——设计意图：最后启动（其余 Bean 就绪后才开跑调度）、
     * 最先停止（关闭时优先停调度、尽量不丢尾批任务）
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
