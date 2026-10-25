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
