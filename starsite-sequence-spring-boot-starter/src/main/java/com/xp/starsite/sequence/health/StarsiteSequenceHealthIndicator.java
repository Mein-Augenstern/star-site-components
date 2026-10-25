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
package com.xp.starsite.sequence.health;

import com.xp.starsite.sequence.StarsiteIdGenerator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * 序列组件健康端点（actuator 存在时装配）：/actuator/health 附 starsiteSequence 明细——
 * 形态与机器位。<b>被动上报，不消耗号段</b>（健康检查高频调用，不能每次都发一个真 id）。
 *
 * @author muyi
 * @since 2026-10
 */
public class StarsiteSequenceHealthIndicator implements HealthIndicator {

    private final StarsiteIdGenerator idGenerator;

    public StarsiteSequenceHealthIndicator(StarsiteIdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("workerId", idGenerator.getWorkerId())
                .withDetail("allocator", idGenerator.describe())
                .build();
    }
}
