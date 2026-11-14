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
package com.xp.starsite.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xp.starsite.quota.api.QuotaClient;
import com.xp.starsite.quota.core.QuotaEngine;
import com.xp.starsite.quota.health.QuotaHealthIndicator;
import com.xp.starsite.quota.store.QuotaStore;
import com.xp.starsite.quota.store.memory.MemoryQuotaStore;
import com.xp.starsite.quota.store.redis.RedisQuotaStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * starter 自动装配测试：memory 默认全家桶 / 门面可用（端到端恰额边界）/ redis 装配与
 * 缺模板快速失败 / enabled 让位 / 自定义 store 让位 / 健康降级与可计数对照。
 *
 * @author muyi
 * @date 2026/11/14
 */
class QuotaAutoConfigurationTest {

    /** 上下文运行器 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(QuotaAutoConfiguration.class));

    /** 提供 StringRedisTemplate 桩的配置 */
    @Configuration
    static class RedisConfig {

        /** 模板桩（execute 恒 0=条件不中——装配断言不触发真实 Redis） */
        @Bean
        StringRedisTemplate stringRedisTemplate() {
            StringRedisTemplate tpl = mock(StringRedisTemplate.class);
            when(tpl.execute(any(), anyList(), any())).thenReturn(0L);
            return tpl;
        }
    }

    /** 自定义存储让位配置 */
    @Configuration
    static class CustomStoreConfig {

        /** 应用自定义存储 */
        @Bean
        QuotaStore customStore() {
            return new MemoryQuotaStore();
        }
    }

    /** 默认 memory 形态 + 引擎/健康端点齐装，门面端到端可用 */
    @Test
    void defaultMemoryStoreAndFacadeWorks() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(QuotaStore.class);
            assertThat(ctx).getBean(QuotaStore.class).isInstanceOf(MemoryQuotaStore.class);
            assertThat(ctx).hasSingleBean(QuotaEngine.class);
            assertThat(ctx).hasSingleBean(QuotaClient.class); // 引擎即门面
            assertThat(ctx).hasSingleBean(QuotaHealthIndicator.class);
            // 端到端冒烟：恰额边界
            QuotaClient quota = ctx.getBean(QuotaClient.class);
            quota.init("smoke", 100);
            assertThat(quota.occupy("smoke", 100).getAvailable()).isZero();
            assertThat(ctx.getBean(QuotaHealthIndicator.class).health().getDetails())
                    .containsEntry("accounts", 1L);
        });
    }

    /** store=redis + 模板在场 → RedisQuotaStore 装配；健康端点观测降级省略明细 */
    @Test
    void redisStoreWiredWhenTemplatePresent() {
        runner.withPropertyValues("starsite.quota.store=redis")
                .withUserConfiguration(RedisConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).getBean(QuotaStore.class).isInstanceOf(RedisQuotaStore.class);
                    assertThat(ctx.getBean(QuotaHealthIndicator.class).health().getDetails())
                            .isEmpty(); // -1 省略
                });
    }

    /** store=redis 缺 StringRedisTemplate → 启动失败并给引导（不静默降级） */
    @Test
    void redisStoreFailsFastWithoutTemplate() {
        runner.withPropertyValues("starsite.quota.store=redis").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasMessageContaining("StringRedisTemplate");
        });
    }

    /** enabled=false 整体让位 */
    @Test
    void disabledPropertyYieldsEntirely() {
        runner.withPropertyValues("starsite.quota.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(QuotaEngine.class);
            assertThat(ctx).doesNotHaveBean(QuotaStore.class);
        });
    }

    /** 自定义存储 Bean 让位（引擎仍装配并采用自定义存储） */
    @Test
    void customStoreBeanTakesPrecedence() {
        runner.withUserConfiguration(CustomStoreConfig.class).run(ctx -> {
            assertThat(ctx).getBean(QuotaStore.class)
                    .isSameAs(ctx.getBean(CustomStoreConfig.class).customStore());
            assertThat(ctx).hasSingleBean(QuotaEngine.class);
        });
    }

    /** 显式 store=memory 语义等价 */
    @Test
    void explicitMemoryStoreHonored() {
        runner.withPropertyValues("starsite.quota.store=memory")
                .run(ctx -> assertThat(ctx).getBean(QuotaStore.class)
                        .isInstanceOf(MemoryQuotaStore.class));
    }
}
