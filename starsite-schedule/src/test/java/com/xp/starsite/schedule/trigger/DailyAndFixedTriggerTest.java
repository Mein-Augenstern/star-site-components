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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * DailyAt / FixedDelay 触发器测试。
 *
 * @author muyi
 * @date 2026/11/02
 */
class DailyAndFixedTriggerTest {

    /** 统一时区 */
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    @Test
    void dailyAtTodayNotYet() {
        DailyAtTrigger t = new DailyAtTrigger(20, 0, CN);
        assertThat(t.nextAfter(LocalDateTime.of(2026, 11, 2, 12, 44, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 2, 20, 0, 0));
    }

    @Test
    void dailyAtAlreadyPastRollsTomorrow() {
        DailyAtTrigger t = new DailyAtTrigger(8, 0, CN);
        assertThat(t.nextAfter(LocalDateTime.of(2026, 11, 2, 8, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 3, 8, 0, 0));  // 恰等于=已过（严格晚于）
        assertThat(t.nextAfter(LocalDateTime.of(2026, 11, 2, 23, 59, 59)))
                .isEqualTo(LocalDateTime.of(2026, 11, 3, 8, 0, 0));
    }

    @Test
    void dailyAtInvalidTime() {
        assertThatThrownBy(() -> new DailyAtTrigger(24, 0, CN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DailyAtTrigger(8, 60, CN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dailyAtDescribe() {
        assertThat(new DailyAtTrigger(20, 5, CN).describe())
                .isEqualTo("dailyAt 20:05 @Asia/Shanghai");
        assertThat(new DailyAtTrigger(20, 5, CN).rescheduleFromCompletion()).isFalse();
    }

    @Test
    void fixedDelayAddsSeconds() {
        FixedDelayTrigger t = new FixedDelayTrigger(30);
        assertThat(t.nextAfter(LocalDateTime.of(2026, 11, 2, 12, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 2, 12, 0, 30));
        assertThat(t.rescheduleFromCompletion()).isTrue();  // 间隔语义：完成后起算
        assertThat(t.describe()).isEqualTo("fixedDelay 30s");
        assertThatThrownBy(() -> new FixedDelayTrigger(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cronTriggerDescribeCarriesZone() {
        assertThat(new CronTrigger("0 0 8 * * ?", CN).describe())
                .isEqualTo("cron 0 0 8 * * ? @Asia/Shanghai");
        assertThatThrownBy(() -> new CronTrigger("bad", CN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
