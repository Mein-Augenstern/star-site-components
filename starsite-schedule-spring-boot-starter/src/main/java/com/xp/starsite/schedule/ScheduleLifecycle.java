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
package com.xp.starsite.schedule;

import com.xp.starsite.schedule.core.ScheduleEngine;
import org.springframework.context.SmartLifecycle;

/**
 * 引擎生命周期适配（与 async-command 同形）：SmartLifecycle phase = MAX——
 * 容器中最后启动（全部 Bean 就绪、任务注册完毕）、最先停止（在途任务排水）。
 *
 * @author muyi
 * @date 2026/11/02
 */
public class ScheduleLifecycle implements SmartLifecycle {

    /** 引擎（start/stop 委托目标） */
    private final ScheduleEngine engine;

    /** 运行标记 */
    private volatile boolean running = false;

    /**
     * 构造。
     *
     * @param engine 调度引擎
     */
    public ScheduleLifecycle(ScheduleEngine engine) {
        this.engine = engine;
    }

    /** {@inheritDoc} */
    @Override
    public void start() {
        engine.start();
        running = true;
    }

    /** {@inheritDoc} */
    @Override
    public void stop() {
        running = false;
        engine.stop();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isRunning() {
        return running;
    }

    /** {@inheritDoc} 容器最后启动、最先停止 */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
