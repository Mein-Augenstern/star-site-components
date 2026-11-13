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

import com.xp.starsite.idempotent.api.IdempotentFailedException;
import com.xp.starsite.idempotent.api.StarsiteIdempotent;
import com.xp.starsite.idempotent.store.IdempotentStore;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * BPP 专项测试：键解析三形态（SpEL 参数 / 字面量 / ${} 占位符）、scope 显式值、
 * POJO 回放反序列化、等待目睹失败映射异常、无注解 Bean 零代理开销。
 *
 * @author muyi
 * @date 2026/11/12
 */
class IdempotentBeanPostProcessorTest {

    /** 上下文运行器 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdempotentAutoConfiguration.class));

    /** 回放载荷 POJO（Jackson 往返的典型 DTO 形态） */
    public static class Receipt {

        /** 订单号 */
        private String orderId;

        /** 金额（分） */
        private long amount;

        /** 无参构造（Jackson 反序列化需要） */
        public Receipt() {
        }

        /**
         * 全参构造。
         *
         * @param orderId 订单号
         * @param amount  金额（分）
         */
        public Receipt(String orderId, long amount) {
            this.orderId = orderId;
            this.amount = amount;
        }

        /**
         * 获取订单号。
         *
         * @return 订单号
         */
        public String getOrderId() {
            return orderId;
        }

        /**
         * 设置订单号。
         *
         * @param orderId 订单号
         */
        public void setOrderId(String orderId) {
            this.orderId = orderId;
        }

        /**
         * 获取金额。
         *
         * @return 金额（分）
         */
        public long getAmount() {
            return amount;
        }

        /**
         * 设置金额。
         *
         * @param amount 金额（分）
         */
        public void setAmount(long amount) {
            this.amount = amount;
        }

        /** {@inheritDoc} */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Receipt receipt)) {
                return false;
            }
            return amount == receipt.amount && Objects.equals(orderId, receipt.orderId);
        }

        /** {@inheritDoc} */
        @Override
        public int hashCode() {
            return Objects.hash(orderId, amount);
        }
    }

    /** 键形态覆盖服务 */
    static class KeyFormsService {

        /** SpEL 复合键执行计数 */
        final AtomicInteger spelRuns = new AtomicInteger();

        /** 字面量键执行计数 */
        final AtomicInteger literalRuns = new AtomicInteger();

        /** 占位符键执行计数 */
        final AtomicInteger placeholderRuns = new AtomicInteger();

        /** SpEL 复合键（拼接参数） */
        @StarsiteIdempotent(key = "'export-' + #day", scope = "report")
        public String spel(String day) {
            spelRuns.incrementAndGet();
            return "report-" + day;
        }

        /** 字面量键（固定键=单飞闸：全应用同时只允许一个在执行） */
        @StarsiteIdempotent(key = "daily-settle", scope = "settle")
        public String literal() {
            literalRuns.incrementAndGet();
            return "settled";
        }

        /** 占位符键（yml 集中管理） */
        @StarsiteIdempotent(key = "${demo.idem-key}")
        public String placeholder() {
            placeholderRuns.incrementAndGet();
            return "ph";
        }
    }

    /** POJO 回放服务 */
    static class ReceiptService {

        /** 执行计数 */
        final AtomicInteger runs = new AtomicInteger();

        /** 幂等下单：返回 POJO（回放经 Jackson 反序列化为同值对象） */
        @StarsiteIdempotent(key = "#orderId", scope = "receipt")
        public Receipt create(String orderId) {
            runs.incrementAndGet();
            return new Receipt(orderId, 9900L);
        }
    }

    /** 等待目睹失败服务 */
    static class FlakyService {

        /** 执行计数 */
        final AtomicInteger runs = new AtomicInteger();

        /** 会失败的动作 */
        @StarsiteIdempotent(key = "#k", scope = "flaky")
        public String boom(String k) {
            runs.incrementAndGet();
            throw new IllegalStateException("下游 500");
        }
    }

    /** 无注解 Bean（零代理） */
    static class PlainService {
    }

    /** 服务装配（原始实例提为字段——计数断言读原始，代理经 Objenesis 构造字段为 null） */
    @Configuration
    static class ServiceConfig {

        /** 键形态服务 */
        final KeyFormsService keyForms = new KeyFormsService();

        /** 回放服务 */
        final ReceiptService receiptService = new ReceiptService();

        /** 失败服务 */
        final FlakyService flaky = new FlakyService();

        /** 键形态服务 Bean */
        @Bean
        KeyFormsService keyForms() {
            return keyForms;
        }

        /** 回放服务 Bean */
        @Bean
        ReceiptService receipt() {
            return receiptService;
        }

        /** 失败服务 Bean */
        @Bean
        FlakyService flaky() {
            return flaky;
        }

        /** 无注解服务 Bean */
        @Bean
        PlainService plain() {
            return new PlainService();
        }
    }

    /** 键三形态：SpEL 复合 / 字面量 / ${} 占位符——各自独立记录且二次回放 */
    @Test
    void keyFormsResolveIndependently() {
        runner.withPropertyValues("demo.idem-key=ph-key")
                .withUserConfiguration(ServiceConfig.class).run(ctx -> {
                    KeyFormsService s = ctx.getBean(KeyFormsService.class);
                    ServiceConfig cfg = ctx.getBean(ServiceConfig.class);
                    assertThat(s.spel("2026-11-12")).isEqualTo("report-2026-11-12");
                    assertThat(s.spel("2026-11-12")).isEqualTo("report-2026-11-12");
                    assertThat(cfg.keyForms.spelRuns.get()).isEqualTo(1);
                    assertThat(s.literal()).isEqualTo("settled");
                    assertThat(s.literal()).isEqualTo("settled");
                    assertThat(cfg.keyForms.literalRuns.get()).isEqualTo(1);
                    assertThat(s.placeholder()).isEqualTo("ph");
                    assertThat(s.placeholder()).isEqualTo("ph");
                    assertThat(cfg.keyForms.placeholderRuns.get()).isEqualTo(1);
                    // 记录键与 scope 形态
                    var store = ctx.getBean(IdempotentStore.class);
                    assertThat(store.find("report", "export-2026-11-12")).isPresent();
                    assertThat(store.find("settle", "daily-settle")).isPresent();
                    assertThat(store.find("IdempotentBeanPostProcessorTest$KeyFormsService#placeholder",
                            "ph-key")).isEmpty(); // 占位符键也走缺省 scope（类#方法）
                });
    }

    /** POJO 回放：反序列化为同值对象（不是同引用——值等价） */
    @Test
    void pojoReplayDeserializes() {
        runner.withUserConfiguration(ServiceConfig.class).run(ctx -> {
            ReceiptService s = ctx.getBean(ReceiptService.class);
            Receipt first = s.create("o-9");
            Receipt second = s.create("o-9");
            assertThat(second).as("回放值等价").isEqualTo(first);
            assertThat(second).isNotSameAs(first); // 反序列化新对象
            assertThat(ctx.getBean(ServiceConfig.class).receiptService.runs.get()).isEqualTo(1);
        });
    }

    /** 等待目睹失败：第二个并发调用映射 IdempotentFailedException（不自动重跑） */
    @Test
    void failedWitnessMapsToException() throws Exception {
        runner.withPropertyValues("starsite.idempotent.wait-millis=2000",
                        "starsite.idempotent.poll-millis=50")
                .withUserConfiguration(ServiceConfig.class).run(ctx -> {
                    FlakyService s = ctx.getBean(FlakyService.class);
                    var store = ctx.getBean(IdempotentStore.class);
                    int runsBefore = ctx.getBean(ServiceConfig.class).flaky.runs.get();
                    // 第一代失败落 FAILED
                    try {
                        s.boom("k-1");
                        throw new AssertionError("应抛原异常");
                    } catch (IllegalStateException expected) {
                        // 原异常重抛语义
                    }
                    // 预置"等待目睹失败"：手工落 PROCESSING 在飞行，动作线程内再置 FAILED
                    store.tryClaim("flaky", "k-2", LocalDateTime.now().plusHours(24), "other:1");
                    Thread failureSetter = new Thread(() -> {
                        try {
                            Thread.sleep(300); // 让等待者先进入在飞轮询（等待窗口 2000ms 内）
                            var row = store.find("flaky", "k-2").orElseThrow();
                            store.fail(row.getId(), row.getExpireAt(), "IllegalStateException: 下游 500");
                        } catch (Exception ignored) {
                            // 时序兜底
                        }
                    });
                    failureSetter.start();
                    try {
                        s.boom("k-2");
                        throw new AssertionError("应映射 IdempotentFailedException");
                    } catch (IdempotentFailedException expected) {
                        assertThat(expected.getMessage()).contains("下游 500");
                    }
                    assertThat(ctx.getBean(ServiceConfig.class).flaky.runs.get())
                            .as("等待者零执行").isEqualTo(runsBefore + 1); // 只有 k-1 那次
                });
    }

    /** 无注解 Bean 不被代理（零开销） */
    @Test
    void plainBeanNotProxied() {
        runner.withUserConfiguration(ServiceConfig.class).run(ctx -> {
            assertThat(AopUtils.isAopProxy(ctx.getBean(PlainService.class))).isFalse();
            assertThat(AopUtils.isAopProxy(ctx.getBean(KeyFormsService.class))).isTrue();
        });
    }
}
