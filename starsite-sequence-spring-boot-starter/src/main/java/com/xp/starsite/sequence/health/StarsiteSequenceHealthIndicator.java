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
 * @date 2026/10/25
 */
public class StarsiteSequenceHealthIndicator implements HealthIndicator {

    /** 发号器门面（仅读取机器位与形态描述，不触发发号） */
    private final StarsiteIdGenerator idGenerator;

    /**
     * 以主装配产出的发号器门面构造健康端点。
     *
     * @param idGenerator 发号器门面（用于读取 workerId 与形态描述；被动上报，不调用发号方法）
     */
    public StarsiteSequenceHealthIndicator(StarsiteIdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    /**
     * 上报序列组件健康信息：恒为 UP（端点能被装配并调用即视为组件就绪），
     * 明细含 workerId（当前机器位）与 allocator（形态与分配器描述，如 standalone/fixed(1)）。
     * <p>被动上报语义：不调用 next()、不消耗号段——健康检查会被高频触发，不能每次探活都产出一个真 id。
     *
     * @return 健康信息（UP + workerId/allocator 两条明细）
     */
    @Override
    public Health health() {
        return Health.up()
                .withDetail("workerId", idGenerator.getWorkerId())
                .withDetail("allocator", idGenerator.describe())
                .build();
    }
}
