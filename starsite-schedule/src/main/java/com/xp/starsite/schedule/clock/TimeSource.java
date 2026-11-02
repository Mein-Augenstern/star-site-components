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
package com.xp.starsite.schedule.clock;

import java.time.LocalDateTime;

/**
 * 时间源抽象（沿用 async-command 模式）：引擎与流水的时间一律经此取，
 * 测试注入假时钟推进（misfire/TTL/看门狗语义的确定性验证前提）。
 *
 * @author muyi
 * @date 2026/11/02
 */
@FunctionalInterface
public interface TimeSource {

    /**
     * 返回当前时间。
     *
     * @return 当前时间；system 实现为 {@code LocalDateTime.now()}，测试实现由假时钟推进
     */
    LocalDateTime now();

    /**
     * 系统时钟实现。
     *
     * @return 委托 {@code LocalDateTime.now()} 的时间源（无状态，线程安全）
     */
    static TimeSource system() {
        return LocalDateTime::now;
    }
}
