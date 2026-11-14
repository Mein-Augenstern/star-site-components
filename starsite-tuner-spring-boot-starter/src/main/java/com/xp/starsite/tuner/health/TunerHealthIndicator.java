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
package com.xp.starsite.tuner.health;

import com.xp.starsite.tuner.core.TunerEngine;
import com.xp.starsite.tuner.store.TunerStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * 治理健康端点（actuator 存在时装配）：/actuator/health 附 starsiteTuner 明细——
 * 受管池数 / 全局绑定数。UP 恒定（调参拒绝是治理常态）；存储异常经传播判 DOWN。
 *
 * @author muyi
 * @date 2026/11/14
 */
public class TunerHealthIndicator implements HealthIndicator {

    /** 引擎（受管池清单） */
    private final TunerEngine engine;

    /** 治理存储（绑定计数——治理面全局可见） */
    private final TunerStore store;

    /**
     * 构造。
     *
     * @param engine 引擎
     * @param store  治理存储
     */
    public TunerHealthIndicator(TunerEngine engine, TunerStore store) {
        this.engine = engine;
        this.store = store;
    }

    /**
     * 计算健康状态：明细附 pools（本实例受管池数）与 bindings（全局绑定数）。
     *
     * @return UP + 明细
     */
    @Override
    public Health health() {
        return Health.up()
                .withDetail("pools", engine.pools().size())
                .withDetail("bindings", store.countBindings())
                .build();
    }
}
