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
package com.xp.starsite.quota.health;

import com.xp.starsite.quota.store.QuotaStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * 配额组件健康端点（actuator 存在时装配）：/actuator/health 附 starsiteQuota 明细——
 * 账户数（<b>观测降级</b>：redis 形态无全库计数返回 -1，明细省略）。
 * UP 恒定（配额拒绝是业务常态不是不健康）；存储访问异常经传播由 actuator 判 DOWN。
 *
 * @author muyi
 * @date 2026/11/14
 */
public class QuotaHealthIndicator implements HealthIndicator {

    /** 配额存储（账户计数读取） */
    private final QuotaStore store;

    /**
     * 构造健康端点。
     *
     * @param store 配额存储
     */
    public QuotaHealthIndicator(QuotaStore store) {
        this.store = store;
    }

    /**
     * 计算健康状态：明细附账户数（&lt; 0 省略——观测降级形态）。
     *
     * @return UP + 账户数明细
     */
    @Override
    public Health health() {
        long accounts = store.countAccounts();
        Health.Builder builder = Health.up();
        if (accounts >= 0) {
            builder.withDetail("accounts", accounts);
        }
        return builder.build();
    }
}
