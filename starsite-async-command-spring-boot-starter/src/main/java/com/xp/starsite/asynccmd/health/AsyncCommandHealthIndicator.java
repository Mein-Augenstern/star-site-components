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

import com.xp.starsite.asynccmd.model.AsyncCommand;
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
 * @date 2026/10/25
 */
public class AsyncCommandHealthIndicator implements HealthIndicator {

    /** 命令存储（健康检查读取各状态计数与到期积压） */
    private final AsyncCommandStore store;

    /**
     * 构造健康端点。
     *
     * @param store 命令存储（每次 health() 调用实时查询计数）
     */
    public AsyncCommandHealthIndicator(AsyncCommandStore store) {
        this.store = store;
    }

    /**
     * 计算异步命令健康状态：明细附 pending/running/dead/backlogDue 四项。
     * <p>判定语义：存在死信即 DOWN——死信代表有命令达到重试上限仍未成功，需要人工处置；
     * 静默吞掉比告警更危险，故宁可让健康检查失败触发上游告警。
     * <p><b>存储异常降级 DOWN</b>（P2 修复，对齐 Boot 自家指标的异常约定）：存储不可达
     * 时返回 DOWN + 错误明细，而非把异常抛出 health() 让端点变裸 500——探针与告警
     * 拿到的是"不健康"信号（可路由可告警），不是"端点炸了"。
     *
     * @return 死信数大于 0 时返回 DOWN（附 reason 说明需人工处置）；存储不可达时返回
     *         DOWN（附错误摘要）；否则返回 UP
     */
    @Override
    public Health health() {
        long pending;
        long running;
        long dead;
        long backlogDue;
        try {
            pending = store.countByStatus(AsyncCommand.PENDING);
            running = store.countByStatus(AsyncCommand.RUNNING);
            dead = store.countByStatus(AsyncCommand.DEAD);
            backlogDue = store.countBacklogDue(LocalDateTime.now());
        } catch (RuntimeException e) {
            // 存储不可达 = 无法判定健康：DOWN + 异常明细（withException），不向上抛
            return Health.down(e).withDetail("reason", "存储不可达，健康无法判定").build();
        }
        // 存在死信即判 DOWN：死信代表重试耗尽仍未成功，需人工处置，不能静默
        Health.Builder builder = dead > 0 ? Health.down() : Health.up();
        builder.withDetail("pending", pending)
                .withDetail("running", running)
                .withDetail("dead", dead)
                .withDetail("backlogDue", backlogDue);
        if (dead > 0) {
            builder.withDetail("reason", "存在死信命令，需人工处置（管控端点或排障）");
        }
        return builder.build();
    }
}
