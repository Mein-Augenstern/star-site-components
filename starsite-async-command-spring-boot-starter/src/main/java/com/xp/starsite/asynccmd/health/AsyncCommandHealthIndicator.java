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
package com.xp.starsite.asynccmd.health;

import com.xp.starsite.asynccmd.AsyncCommand;
import com.xp.starsite.asynccmd.store.AsyncCommandStore;
import java.time.LocalDateTime;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * 异步命令健康端点（actuator 存在时装配）：/actuator/health 附 starsiteAsyncCommand 明细——
 * 各状态计数与到期积压；<b>存在死信即 DOWN</b>（死信代表有命令达到重试上限仍未成功，
 * 需要人工处置，静默吞掉比告警更危险）。
 *
 * @author muyi
 * @since 2026-10
 */
public class AsyncCommandHealthIndicator implements HealthIndicator {

    private final AsyncCommandStore store;

    public AsyncCommandHealthIndicator(AsyncCommandStore store) {
        this.store = store;
    }

    @Override
    public Health health() {
        long pending = store.countByStatus(AsyncCommand.PENDING);
        long running = store.countByStatus(AsyncCommand.RUNNING);
        long dead = store.countByStatus(AsyncCommand.DEAD);
        Health.Builder builder = dead > 0 ? Health.down() : Health.up();
        builder.withDetail("pending", pending)
                .withDetail("running", running)
                .withDetail("dead", dead)
                .withDetail("backlogDue", store.countBacklogDue(LocalDateTime.now()));
        if (dead > 0) {
            builder.withDetail("reason", "存在死信命令，需人工处置（管控端点或排障）");
        }
        return builder.build();
    }
}
