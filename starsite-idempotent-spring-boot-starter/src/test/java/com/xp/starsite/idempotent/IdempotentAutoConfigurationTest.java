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

import com.xp.starsite.idempotent.api.IdempotentConflictException;
import com.xp.starsite.idempotent.api.IdempotentStatus;
import com.xp.starsite.idempotent.api.StarsiteIdempotent;
import com.xp.starsite.idempotent.core.IdempotentEngine;
import com.xp.starsite.idempotent.health.IdempotentHealthIndicator;
import com.xp.starsite.idempotent.store.IdempotentStore;
import com.xp.starsite.idempotent.store.MemoryIdempotentStore;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * starter 自动装配测试：memory 默认形态、引擎/看护/健康端点齐装、注解 Bean 走代理
 * 端到端（执行一次 + 二次回放 + 冲突映射异常）、enabled=false 整体让位、
 * 自定义存储让位、retention 开关。
 *
 * @author muyi
 * @date 2026/11/12
 */
class IdempotentAutoConfigurationTest {

    /** 上下文运行器 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdempotentAutoConfiguration.class));

    /** 幂等下单服务（命名类——scope 缺省 类简单名#方法名 依赖命名） */
    static class OrderService {

        /** 执行计数（回放断言依据） */
        final AtomicInteger runs = new AtomicInteger();

        /** 幂等下单端点形态：同 orderId 只执行一次，窗口内回放首次结果 */
        @StarsiteIdempotent(key = "#orderId")
        public String create(String orderId) {
            runs.incrementAndGet();
            return "created-" + orderId;
        }
    }

    /** 带注解方法的业务 Bean（幂等代理端到端） */
    @Configuration
    static class OrderConfig {

        /** 原始服务实例（计数断言读它——CGLIB 代理经 Objenesis 构造，字段初始化器不跑） */
        final OrderService service = new OrderService();

        /** 幂等下单服务 Bean */
        @Bean
        OrderService orderService() {
            return service;
        }
    }

    /** 自定义存储让位验证配置 */
    @Configuration
    static class CustomStoreConfig {

        /** 应用自定义存储（自动装配让位） */
        @Bean
        IdempotentStore customStore() {
            return new MemoryIdempotentStore(() -> 999L);
        }
    }

    /** 默认 memory 形态 + 全套 Bean */
    @Test
    void defaultMemoryStoreAndCoreBeans() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(IdempotentStore.class);
            assertThat(ctx).getBean(IdempotentStore.class).isInstanceOf(MemoryIdempotentStore.class);
            assertThat(ctx).hasSingleBean(IdempotentEngine.class);
            assertThat(ctx).hasSingleBean(IdempotentRetentionLifecycle.class);
            assertThat(ctx).hasSingleBean(IdempotentHealthIndicator.class);
            assertThat(ctx).hasSingleBean(IdempotentBeanPostProcessor.class);
            // 健康端点三态计数可用
            var health = ctx.getBean(IdempotentHealthIndicator.class).health();
            assertThat(health.getStatus().toString()).isEqualTo("UP");
            assertThat(health.getDetails()).containsKeys("processing", "done", "failed");
        });
    }

    /** 注解 Bean 代理端到端：首调执行、二调回放（动作恰一次）、记录落 DONE */
    @Test
    void annotatedBeanProxiedWithReplay() {
        runner.withUserConfiguration(OrderConfig.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            OrderService proxy = ctx.getBean(OrderService.class);
            assertThat(AopUtils.isAopProxy(proxy)).as("注解 Bean 被代理").isTrue();
            OrderService original = ctx.getBean(OrderConfig.class).service;
            String first = proxy.create("o-100");
            String second = proxy.create("o-100");
            assertThat(first).isEqualTo("created-o-100");
            assertThat(second).as("回放与首次一致").isEqualTo("created-o-100");
            assertThat(original.runs.get()).as("动作恰执行一次").isEqualTo(1);
            // 记录落 DONE（scope 缺省 类#方法）
            var store = ctx.getBean(IdempotentStore.class);
            assertThat(store.countByStatus(IdempotentStatus.DONE)).isEqualTo(1);
        });
    }

    /** 冲突映射：他方在飞且等待窗口耗尽 → IdempotentConflictException */
    @Test
    void conflictMapsToExceptionThroughProxy() {
        runner.withPropertyValues("starsite.idempotent.wait-millis=150")
                .withUserConfiguration(OrderConfig.class).run(ctx -> {
                    var store = ctx.getBean(IdempotentStore.class);
                    // 预置他方在飞（新鲜 PROCESSING——无法在窗口内完成）
                    store.tryClaim("OrderService#create", "o-200",
                            LocalDateTime.now().plusHours(24), "other:1");
                    OrderService proxy = ctx.getBean(OrderService.class);
                    try {
                        proxy.create("o-200");
                        throw new AssertionError("应映射 IdempotentConflictException");
                    } catch (IdempotentConflictException expected) {
                        // 语义断言在类型上
                    }
                    assertThat(ctx.getBean(OrderConfig.class).service.runs.get())
                            .as("冲突路径动作零执行").isEqualTo(0);
                });
    }

    /** enabled=false 整体让位 */
    @Test
    void disabledPropertyYieldsEntirely() {
        runner.withPropertyValues("starsite.idempotent.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(IdempotentEngine.class);
            assertThat(ctx).doesNotHaveBean(IdempotentStore.class);
            assertThat(ctx).doesNotHaveBean(IdempotentRetentionLifecycle.class);
        });
    }

    /** 自定义存储 Bean 让位（引擎仍装配） */
    @Test
    void customStoreBeanTakesPrecedence() {
        runner.withUserConfiguration(CustomStoreConfig.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(IdempotentStore.class);
            assertThat(ctx).getBean(IdempotentStore.class)
                    .isSameAs(ctx.getBean(CustomStoreConfig.class).customStore());
            assertThat(ctx).hasSingleBean(IdempotentEngine.class);
        });
    }

    /** retention-enabled=false 不装配看护 */
    @Test
    void retentionDisableHonored() {
        runner.withPropertyValues("starsite.idempotent.retention-enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(IdempotentRetentionLifecycle.class));
    }

    /** 显式 store=memory 语义等价（非法值由 Spring 枚举绑定拒绝——配置键迁移巡检原则） */
    @Test
    void explicitMemoryStoreHonored() {
        runner.withPropertyValues("starsite.idempotent.store=memory")
                .run(ctx -> assertThat(ctx).getBean(IdempotentStore.class)
                        .isInstanceOf(MemoryIdempotentStore.class));
    }
}
