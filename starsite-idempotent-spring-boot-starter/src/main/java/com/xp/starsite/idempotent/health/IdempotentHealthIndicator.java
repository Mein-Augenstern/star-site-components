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
package com.xp.starsite.idempotent.health;

import com.xp.starsite.idempotent.api.IdempotentStatus;
import com.xp.starsite.idempotent.store.IdempotentStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * 幂等组件健康端点（actuator 存在时装配）：/actuator/health 附 starsiteIdempotent
 * 明细——processing/done/failed 三态计数（<b>观测降级</b>：不支持全库计数的形态
 * 如 redis 返回 -1，对应明细省略——健康端点不做 SCAN 代价的查询）。
 *
 * <p>判定语义：FAILED 行<b>不</b>判 DOWN（与 async-command 死信不同——失败是业务
 * 动作的自然结局，键已释放可重试，不是需人工处置的终态积压）；存储访问异常
 * 向上传播由 actuator 纳入探测结果（存储不可达本身即不健康）。
 *
 * @author muyi
 * @date 2026/11/12
 */
public class IdempotentHealthIndicator implements HealthIndicator {

    /** 幂等存储（计数读取） */
    private final IdempotentStore store;

    /**
     * 构造健康端点。
     *
     * @param store 幂等存储（每次 health() 调用实时查询计数）
     */
    public IdempotentHealthIndicator(IdempotentStore store) {
        this.store = store;
    }

    /**
     * 计算幂等组件健康状态：明细附 processing/done/failed 三项计数（计数 &lt; 0 的
     * 省略——观测降级形态无全库计数；UP 恒定，存储异常经传播由 actuator 判 DOWN）。
     *
     * @return UP + 可支持的三态计数明细
     */
    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        detail(builder, "processing", IdempotentStatus.PROCESSING);
        detail(builder, "done", IdempotentStatus.DONE);
        detail(builder, "failed", IdempotentStatus.FAILED);
        return builder.build();
    }

    /**
     * 附计数明细（-1 = 形态不支持全库计数——省略，不虚报 0）。
     *
     * @param builder 健康构建器
     * @param name    明细名
     * @param status  状态
     */
    private void detail(Health.Builder builder, String name, IdempotentStatus status) {
        long count = store.countByStatus(status);
        if (count >= 0) {
            builder.withDetail(name, count);
        }
    }
}
