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
import java.time.ZoneId;

/**
 * 每日固定时刻触发器（HH:mm）——日级任务的最常用形态（invest S09 每晚 20:00 拉数）。
 * 不引 DST 微妙语义：按任务时区的本地钟直接比较推进。
 *
 * @author muyi
 * @date 2026/11/02
 */
public class DailyAtTrigger implements Trigger {

    /** 时刻的时（0-23） */
    private final int hour;

    /** 时刻的分（0-59） */
    private final int minute;

    /** 触发时区 */
    private final ZoneId zone;

    /**
     * 构造。
     *
     * @param hour   时（0-23）
     * @param minute 分（0-59）
     * @param zone   时区
     * @throws IllegalArgumentException 时刻越界
     */
    public DailyAtTrigger(int hour, int minute, ZoneId zone) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw new IllegalArgumentException("dailyAt 时刻越界: " + hour + ":" + minute);
        }
        this.hour = hour;
        this.minute = minute;
        this.zone = zone;
    }

    /** {@inheritDoc} */
    @Override
    public LocalDateTime nextAfter(LocalDateTime base) {
        LocalDateTime candidate = base.toLocalDate().atTime(hour, minute);
        if (!candidate.isAfter(base)) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    /** {@inheritDoc} */
    @Override
    public String describe() {
        return "dailyAt " + String.format("%02d:%02d", hour, minute) + " @" + zone.getId();
    }
}
