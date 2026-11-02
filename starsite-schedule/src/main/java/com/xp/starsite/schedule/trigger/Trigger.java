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
 * 触发器（触发层）：由基准时刻推算下一触发时刻。纯函数无状态——引擎持有状态，
 * 同一实例可共享触发器对象。
 *
 * <p>实现：{@link CronTrigger}（6 字段 cron）/ {@link DailyAtTrigger}（每日固定时刻）/
 * {@link FixedDelayTrigger}（固定间隔）。
 *
 * @author muyi
 * @date 2026/11/02
 */
public interface Trigger {

    /**
     * 下一触发时刻（严格晚于 base）。
     *
     * @param base 基准时刻（cron/dailyAt=上次计划触发时刻；fixedDelay=上次完成时刻，
     *             由引擎按 {@link #rescheduleFromCompletion()} 选取）
     * @return 下一触发时刻
     */
    LocalDateTime nextAfter(LocalDateTime base);

    /**
     * 是否以上次<b>完成</b>时刻为基准（true=间隔语义，慢任务不堆积；false=时刻表语义）。
     *
     * @return 默认 false（cron/dailyAt）；FixedDelayTrigger 为 true
     */
    default boolean rescheduleFromCompletion() {
        return false;
    }

    /**
     * 人类可读描述（TaskStatus.trigger 展示用）。
     *
     * @return 如 {@code "cron 0 0 8 * * ? @Asia/Shanghai"}
     */
    String describe();
}
