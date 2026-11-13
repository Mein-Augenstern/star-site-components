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
package com.xp.starsite.idempotent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xp.starsite.idempotent.health.IdempotentHealthIndicator;
import com.xp.starsite.idempotent.store.IdempotentStore;
import com.xp.starsite.idempotent.store.RedisIdempotentStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * redis 形态装配与降级测试：store=redis 装配 RedisIdempotentStore、
 * 缺 StringRedisTemplate 快速失败（显式配置错误不静默降级）、
 * 观测降级 store 的健康端点省略计数明细。
 *
 * @author muyi
 * @date 2026/11/13
 */
class IdempotentRedisAutoConfigurationTest {

    /** 上下文运行器 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdempotentAutoConfiguration.class));

    /** 提供 StringRedisTemplate 桩的配置 */
    @Configuration
    static class RedisConfig {

        /** hash 操作桩（find 通道返回空 map = 键不存在） */
        @SuppressWarnings("unchecked")
        private final org.springframework.data.redis.core.HashOperations<String, String, String> hashOps =
                mock(org.springframework.data.redis.core.HashOperations.class);

        /** 模板桩（execute 恒 0——装配断言不触发真实 Redis 交互） */
        @Bean
        StringRedisTemplate stringRedisTemplate() {
            StringRedisTemplate tpl = mock(StringRedisTemplate.class);
            when(tpl.execute(any(), anyList(), any())).thenReturn(0L);
            when(tpl.<String, String>opsForHash()).thenReturn(hashOps);
            return tpl;
        }
    }

    /** store=redis + 模板在场 → RedisIdempotentStore 装配（引擎/看护/健康端点同装） */
    @Test
    void redisStoreWiredWhenTemplatePresent() {
        runner.withPropertyValues("starsite.idempotent.store=redis")
                .withUserConfiguration(RedisConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).getBean(IdempotentStore.class)
                            .isInstanceOf(RedisIdempotentStore.class);
                    assertThat(ctx).hasSingleBean(IdempotentHealthIndicator.class);
                });
    }

    /** store=redis 缺 StringRedisTemplate → 启动失败并给引导（不静默降级 memory） */
    @Test
    void redisStoreFailsFastWithoutTemplate() {
        runner.withPropertyValues("starsite.idempotent.store=redis").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasMessageContaining("StringRedisTemplate");
        });
    }

    /** 自定义前缀透传：store=redis + redis-key-prefix → find 的键带自定义前缀 */
    @Test
    void redisKeyPrefixHonored() {
        runner.withPropertyValues("starsite.idempotent.store=redis",
                        "starsite.idempotent.redis-key-prefix=myapp:idem:")
                .withUserConfiguration(RedisConfig.class)
                .run(ctx -> {
                    IdempotentStore store = ctx.getBean(IdempotentStore.class);
                    store.find("s", "k");
                    org.mockito.Mockito.verify(RedisConfigHolder.hashOpsOf(ctx))
                            .entries("myapp:idem:s\u0001k");
                });
    }

    /** 配置类持有者（测试从上下文配置取桩引用做 verify） */
    private static final class RedisConfigHolder {

        /**
         * 从上下文取配置类实例的 hash 桩。
         *
         * @param ctx 上下文
         * @return hash 桩
         */
        static org.springframework.data.redis.core.HashOperations<String, String, String> hashOpsOf(
                org.springframework.boot.test.context.assertj.AssertableApplicationContext ctx) {
            return ctx.getBean(RedisConfig.class).hashOps;
        }
    }

    /** 观测降级 store（redis：计数 -1）→ 健康端点省略计数明细（不虚报 0） */
    @Test
    void healthIndicatorOmitsDegradedCounts() {
        StringRedisTemplate tpl = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.HashOperations<String, String, String> hashOps =
                mock(org.springframework.data.redis.core.HashOperations.class);
        when(tpl.<String, String>opsForHash()).thenReturn(hashOps);
        IdempotentStore degraded = new RedisIdempotentStore(tpl, "x:");
        var health = new IdempotentHealthIndicator(degraded).health();
        assertThat(health.getStatus().toString()).isEqualTo("UP");
        assertThat(health.getDetails()).isEmpty(); // 三态计数全 -1 → 全省略
    }

    /** 对照：可计数 store（memory）健康明细齐全——降级路径不误伤表/内存形态 */
    @Test
    void healthIndicatorKeepsCountableDetails() {
        IdempotentStore countable = new com.xp.starsite.idempotent.store.MemoryIdempotentStore(
                () -> 1L);
        var health = new IdempotentHealthIndicator(countable).health();
        assertThat(health.getDetails()).containsKeys("processing", "done", "failed");
        assertThat(health.getDetails().get("done")).isEqualTo(0L); // 零值也上报（与 -1 省略区分）
    }
}
