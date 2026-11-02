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
package com.xp.starsite.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.xp.starsite.lock.client.LockClient;
import com.xp.starsite.schedule.api.ScheduleClient;
import com.xp.starsite.schedule.api.StarsiteScheduled;
import com.xp.starsite.schedule.core.ScheduleEngine;
import com.xp.starsite.schedule.core.ScheduleTaskRegistry;
import com.xp.starsite.schedule.store.MemoryRunLogStore;
import com.xp.starsite.schedule.store.RunLogStore;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * starter 自动装配测试：memory 默认形态、注解扫描注册、guard 无锁快速失败、
 * enabled=false 整体让位、BPP 注册先于引擎启动。
 *
 * @author muyi
 * @date 2026/11/02
 */
class ScheduleAutoConfigurationTest {

    /** 上下文运行器（LockClient 由 LockConfig 提供——锁组件装配不在本组件测试范围） */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ScheduleAutoConfiguration.class));

    /** 单测用锁 Bean（tryLock 恒真 + 计数释放） */
    @Configuration
    static class LockConfig {
        @Bean
        LockClient lockClient() {
            Set<String> held = ConcurrentHashMap.newKeySet();
            return new LockClient() {
                @Override
                public boolean tryLock(String lockName) {
                    return held.add(lockName);
                }

                @Override
                public void unlock(String lockName) {
                    held.remove(lockName);
                }
            };
        }
    }

    /** 带注解任务的业务配置 */
    @Configuration
    static class TaskConfig {
        @Bean
        Object scanTask() {
            return new Object() {
                @StarsiteScheduled(name = "scan", cron = "0 0 8 * * ?")
                public void scan() {
                }
            };
        }
    }

    /** cron 走 ${} 占位符的任务配置（Spring @Scheduled 同款 DX——yml 集中管理触发时刻） */
    @Configuration
    static class PlaceholderTaskConfig {
        @Bean
        Object phTask() {
            return new Object() {
                @StarsiteScheduled(name = "${task.name}", cron = "${task.cron}")
                public void ph() {
                }
            };
        }
    }

    @Test
    void placeholderAnnotationValuesResolved() {
        runner.withUserConfiguration(LockConfig.class, PlaceholderTaskConfig.class)
                .withPropertyValues("task.name=ph-scan", "task.cron=0 30 9 * * ?")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    ScheduleTaskRegistry registry = ctx.getBean(ScheduleTaskRegistry.class);
                    assertThat(registry.find("ph-scan")).isPresent();
                    assertThat(registry.find("ph-scan").orElseThrow().getTrigger().describe())
                            .contains("0 30 9 * * ?");
                });
    }

    @Test
    void defaultMemoryStoreAndCoreBeans() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ScheduleTaskRegistry.class);
            assertThat(ctx).hasSingleBean(RunLogStore.class);
            assertThat(ctx).getBean(RunLogStore.class).isInstanceOf(MemoryRunLogStore.class);
            assertThat(ctx).hasSingleBean(ScheduleEngine.class);
            assertThat(ctx).hasSingleBean(ScheduleClient.class);
            assertThat(ctx).hasSingleBean(ScheduleLifecycle.class);
            // 引擎已由 SmartLifecycle 拉起（phase MAX）
            assertThat(ctx.getBean(ScheduleEngine.class).isStarted()).isTrue();
        });
    }

    @Test
    void annotationTaskRegisteredViaBpp() {
        runner.withUserConfiguration(LockConfig.class, TaskConfig.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            ScheduleTaskRegistry registry = ctx.getBean(ScheduleTaskRegistry.class);
            assertThat(registry.size()).isEqualTo(1);
            assertThat(registry.find("scan")).isPresent();
            assertThat(registry.find("scan").orElseThrow().getLockName()).isEqualTo("sch:scan");
            assertThat(ctx.getBean(ScheduleClient.class).status("scan")).isPresent();
        });
    }

    @Test
    void guardTaskFailsFastWithoutLockClient() {
        // 容器缺 LockClient：guard 任务注册失败并说明（引 lock starter 或 guard=false）
        runner.withUserConfiguration(TaskConfig.class).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasMessageContaining("LockClient");
        });
    }

    @Test
    void disabledPropertyYieldsEntirely() {
        runner.withPropertyValues("starsite.schedule.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(ScheduleEngine.class);
            assertThat(ctx).doesNotHaveBean(RunLogStore.class);
        });
    }

    @Test
    void explicitMemoryStoreHonored() {
        runner.withPropertyValues("starsite.schedule.store=memory")
                .run(ctx -> assertThat(ctx).getBean(RunLogStore.class)
                        .isInstanceOf(MemoryRunLogStore.class));
    }
}
