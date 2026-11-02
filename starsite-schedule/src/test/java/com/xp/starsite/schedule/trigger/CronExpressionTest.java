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
 * 自研 6 字段 cron 解析器测试：语法、边界、日/周 OR 语义、2/29 死表达式、
 * 时区推算（UTC 容器 + CST 时区——组件默认值的产品化动机场景）。
 *
 * @author muyi
 * @date 2026/11/02
 */
class CronExpressionTest {

    /** 统一时区（Asia/Shanghai——组件默认） */
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    private static LocalDateTime next(String cron, LocalDateTime after) {
        return new CronExpression(cron).nextAfter(after, CN);
    }

    @Test
    void everySecond() {
        assertThat(next("* * * * * ?", LocalDateTime.of(2026, 11, 2, 12, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 2, 12, 0, 1));
    }

    @Test
    void dailyAtEight() {
        // backend ReminderScanTask 真实形态：每天 08:00
        assertThat(next("0 0 8 * * ?", LocalDateTime.of(2026, 11, 2, 9, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 3, 8, 0, 0));
        assertThat(next("0 0 8 * * ?", LocalDateTime.of(2026, 11, 2, 7, 59, 59)))
                .isEqualTo(LocalDateTime.of(2026, 11, 2, 8, 0, 0));
    }

    @Test
    void strictlyAfterBase() {
        // 基准恰为命中时刻：下一次是下一轮（不重发）
        assertThat(next("0 0 8 * * ?", LocalDateTime.of(2026, 11, 2, 8, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 3, 8, 0, 0));
    }

    @Test
    void minuteStep() {
        // 每 5 分钟（分钟字段 0/5：0 起 5 步——避开注释里的星斜杠写法）
        assertThat(next("0 0/5 * * * ?", LocalDateTime.of(2026, 11, 2, 12, 3, 30)))
                .isEqualTo(LocalDateTime.of(2026, 11, 2, 12, 5, 0));
        assertThat(next("0 0/5 * * * ?", LocalDateTime.of(2026, 11, 2, 12, 5, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 2, 12, 10, 0));
    }

    @Test
    void hourRangeWithStep() {
        // 工作时间每半小时：8-18 点的 0/30 分
        assertThat(next("0 30/30 8-18 * * ?", LocalDateTime.of(2026, 11, 2, 19, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 3, 8, 30, 0));
    }

    @Test
    void commaList() {
        assertThat(next("0 0 6,12,18 * * ?", LocalDateTime.of(2026, 11, 2, 7, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 2, 12, 0, 0));
    }

    @Test
    void dayOfWeekNamesAndSundaySeven() {
        // 周一到周五 08:00（英文名与数字 1-5 等价）
        assertThat(next("0 0 8 ? * MON-FRI", LocalDateTime.of(2026, 11, 2, 9, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 3, 8, 0, 0));  // 2026-11-02 周一已过 8 点
        // 周日=0 与 7 等价
        assertThat(next("0 0 8 ? * 0", LocalDateTime.of(2026, 11, 2, 9, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 8, 8, 0, 0));
        assertThat(next("0 0 8 ? * 7", LocalDateTime.of(2026, 11, 2, 9, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 8, 8, 0, 0));
    }

    @Test
    void dayOfMonthDomOrDowSemantics() {
        // 日/周都受限 = OR（Quartz 语义）：1 号或每周五 08:00——2026-11-02 是周一，
        // 下一命中是 11-06（周五）而非 12-01
        assertThat(next("0 0 8 1 * FRI", LocalDateTime.of(2026, 11, 2, 9, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 6, 8, 0, 0));
    }

    @Test
    void domRestrictedOnly() {
        // 仅日受限：每月 1 号 08:00
        assertThat(next("0 0 8 1 * ?", LocalDateTime.of(2026, 11, 2, 9, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 12, 1, 8, 0, 0));
    }

    @Test
    void monthNamesAndClimb() {
        // 每年 3 月 1 日 08:00：月攀爬跨年
        assertThat(next("0 0 8 1 MAR ?", LocalDateTime.of(2026, 11, 2, 9, 0, 0)))
                .isEqualTo(LocalDateTime.of(2027, 3, 1, 8, 0, 0));
    }

    @Test
    void questionMarkEqualsStar() {
        assertThat(next("0 0 8 ? * ?", LocalDateTime.of(2026, 11, 2, 9, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 3, 8, 0, 0));
    }

    @Test
    void utcZoneComputation() {
        // UTC 容器陷阱产品化场景：同一 cron 同一基准，时区不同物理时刻差 8 小时——
        // 20:00 UTC = 次日 04:00 北京（这正是组件把 zone 默认 Asia/Shanghai 的原因）
        java.time.LocalDateTime base = java.time.LocalDateTime.of(2026, 11, 2, 0, 0, 0);
        java.time.LocalDateTime inUtc =
                new CronExpression("0 0 20 * * ?").nextAfter(base, java.time.ZoneId.of("UTC"));
        java.time.LocalDateTime inCn = new CronExpression("0 0 20 * * ?").nextAfter(base, CN);
        assertThat(inUtc).isEqualTo(java.time.LocalDateTime.of(2026, 11, 2, 20, 0, 0));
        assertThat(inUtc.atZone(java.time.ZoneId.of("UTC"))
                .withZoneSameInstant(CN).toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 11, 3));
        assertThat(inCn).isEqualTo(java.time.LocalDateTime.of(2026, 11, 2, 20, 0, 0));
    }

    @Test
    void leapDayNeverMatchesFailsFast() {
        // 2 月 30 日永不命中：4 年攀爬上界内抛出（装配期暴露）
        assertThatThrownBy(() -> next("0 0 8 30 2 ?", LocalDateTime.of(2026, 1, 1, 0, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无命中");
    }

    @Test
    void invalidExpressions() {
        assertThatThrownBy(() -> new CronExpression("0 0 8 * *")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CronExpression("0 0 25 * * ?")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CronExpression("0 0 8 0 * ?")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CronExpression("60 0 8 * * ?")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CronExpression("0 5-1 * * * ?")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CronExpression("0 0 8 * * ?").nextAfter(null, CN))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void describeRoundTrip() {
        CronExpression cron = new CronExpression("0 0 8 * * ?");
        assertThat(cron.getExpression()).isEqualTo("0 0 8 * * ?");
        assertThat(cron.toString()).contains("0 0 8 * * ?");
    }
}
