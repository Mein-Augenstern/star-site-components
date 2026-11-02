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
 * cron 触发器：持有表达式与时区（时区默认值的产品化动机见 StarsiteScheduled#zone）。
 *
 * @author muyi
 * @date 2026/11/02
 */
public class CronTrigger implements Trigger {

    /** cron 表达式（解析校验在构造期完成——非法表达式装配即暴露） */
    private final CronExpression cron;

    /** 触发时区 */
    private final ZoneId zone;

    /**
     * 构造。
     *
     * @param expression 6 字段 cron 表达式
     * @param zone       时区
     */
    public CronTrigger(String expression, ZoneId zone) {
        this.cron = new CronExpression(expression);
        this.zone = zone;
    }

    /** {@inheritDoc} */
    @Override
    public LocalDateTime nextAfter(LocalDateTime base) {
        return cron.nextAfter(base, zone);
    }

    /** {@inheritDoc} */
    @Override
    public String describe() {
        return "cron " + cron.getExpression() + " @" + zone.getId();
    }
}
