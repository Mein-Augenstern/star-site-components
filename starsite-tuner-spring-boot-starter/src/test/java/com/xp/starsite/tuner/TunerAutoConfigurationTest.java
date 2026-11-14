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
package com.xp.starsite.tuner;

import static org.assertj.core.api.Assertions.assertThat;

import com.xp.starsite.tuner.api.TunerClient;
import com.xp.starsite.tuner.api.TunerPreset;
import com.xp.starsite.tuner.core.TunerEngine;
import com.xp.starsite.tuner.health.TunerHealthIndicator;
import com.xp.starsite.tuner.store.TunerStore;
import com.xp.starsite.tuner.store.memory.MemoryTunerStore;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * starter 自动装配测试：memory 默认全家桶 / 门面端到端（注册→预案→reload 生效）/
 * enabled 让位 / 自定义 store 让位 / 健康明细 / jdbc 形态（H2 DataSource）。
 *
 * @author muyi
 * @date 2026/11/14
 */
class TunerAutoConfigurationTest {

    /** 上下文运行器 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TunerAutoConfiguration.class));

    /** 受管池配置 */
    @Configuration
    static class PoolConfig {

        /** 业务线程池（有界队列——治理收益形态） */
        @Bean
        ThreadPoolExecutor orderPool() {
            return new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(100));
        }
    }

    /** 自定义存储配置 */
    @Configuration
    static class CustomStoreConfig {

        /** 应用自定义存储 */
        @Bean
        TunerStore customStore() {
            return new MemoryTunerStore(() -> java.time.LocalDateTime.now());
        }
    }

    /** 默认 memory 形态：引擎/生命周期/健康端点齐装，门面端到端（注册→预案→reload 生效） */
    @Test
    void defaultWiringAndEndToEnd() {
        runner.withUserConfiguration(PoolConfig.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(TunerStore.class);
            assertThat(ctx).getBean(TunerStore.class).isInstanceOf(MemoryTunerStore.class);
            assertThat(ctx).hasSingleBean(TunerEngine.class);
            assertThat(ctx).hasSingleBean(TunerClient.class);
            assertThat(ctx).hasSingleBean(TunerLifecycle.class);
            assertThat(ctx).hasSingleBean(TunerHealthIndicator.class);

            // 端到端：注册 → 预案 → 绑定 → reload 生效
            TunerClient client = ctx.getBean(TunerClient.class);
            ThreadPoolExecutor pool = ctx.getBean(ThreadPoolExecutor.class);
            client.register("order-pool", pool);
            client.upsertPreset(new TunerPreset("daily", 6, 8, 120));
            client.bindDefault("order-pool", "daily");
            client.reload();
            assertThat(pool.getCorePoolSize()).isEqualTo(6);
            assertThat(client.recentAudits(5)).hasSize(1);
            // 健康明细
            health = ctx.getBean(TunerHealthIndicator.class).health();
            assertThat(health.getDetails()).containsEntry("pools", 1)
                    .containsEntry("bindings", 1L);
        });
    }

    /** enabled=false 整体让位 */
    @Test
    void disabledPropertyYieldsEntirely() {
        runner.withPropertyValues("starsite.tuner.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(TunerEngine.class);
            assertThat(ctx).doesNotHaveBean(TunerStore.class);
            assertThat(ctx).doesNotHaveBean(TunerLifecycle.class);
        });
    }

    /** 自定义存储 Bean 让位 */
    @Test
    void customStoreBeanTakesPrecedence() {
        runner.withUserConfiguration(CustomStoreConfig.class).run(ctx -> {
            assertThat(ctx).getBean(TunerStore.class)
                    .isSameAs(ctx.getBean(CustomStoreConfig.class).customStore());
            assertThat(ctx).hasSingleBean(TunerEngine.class);
        });
    }

    /** jdbc 形态（H2 DataSource 在场）：JdbcTunerStore 装配且四表自动建立 */
    @Test
    void jdbcStoreWiredWithDataSource() {
        runner.withPropertyValues("starsite.tuner.store=jdbc",
                        "starsite.tuner.table-prefix=tuner")
                .withUserConfiguration(DataSourceConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).getBean(TunerStore.class)
                            .isInstanceOf(com.xp.starsite.tuner.store.jdbc.JdbcTunerStore.class);
                });
    }

    /** H2 数据源 + JdbcTemplate 配置（jdbc 形态用；真 Boot 由 JdbcTemplateAutoConfiguration 提供） */
    @Configuration
    static class DataSourceConfig {

        /** H2 DataSource（内存库） */
        @Bean
        javax.sql.DataSource dataSource() {
            return new org.springframework.jdbc.datasource.SimpleDriverDataSource() {
                {
                    setDriverClass(org.h2.Driver.class);
                    setUrl("jdbc:h2:mem:tuner-starter-test;DB_CLOSE_DELAY=-1");
                    setUsername("sa");
                    setPassword("");
                }
            };
        }

        /** JdbcTemplate（模拟 Boot 自动装配） */
        @Bean
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate(javax.sql.DataSource ds) {
            return new org.springframework.jdbc.core.JdbcTemplate(ds);
        }
    }
}
