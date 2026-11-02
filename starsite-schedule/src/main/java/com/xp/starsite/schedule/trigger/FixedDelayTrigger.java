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
package com.xp.starsite.schedule.trigger;

import java.time.LocalDateTime;

/**
 * 固定间隔触发器（秒）：以上次<b>完成</b>时刻为基准（间隔语义，慢任务不堆积）。
 * 引擎见 {@link #rescheduleFromCompletion()} 选取基准。
 *
 * @author muyi
 * @date 2026/11/02
 */
public class FixedDelayTrigger implements Trigger {

    /** 间隔秒数（正数） */
    private final long delaySeconds;

    /**
     * 构造。
     *
     * @param delaySeconds 间隔秒数
     * @throws IllegalArgumentException 非正数
     */
    public FixedDelayTrigger(long delaySeconds) {
        if (delaySeconds <= 0) {
            throw new IllegalArgumentException("fixedDelaySeconds 必须为正: " + delaySeconds);
        }
        this.delaySeconds = delaySeconds;
    }

    /** {@inheritDoc} */
    @Override
    public LocalDateTime nextAfter(LocalDateTime base) {
        return base.plusSeconds(delaySeconds);
    }

    /** {@inheritDoc} 间隔语义：基准取完成时刻 */
    @Override
    public boolean rescheduleFromCompletion() {
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public String describe() {
        return "fixedDelay " + delaySeconds + "s";
    }
}
