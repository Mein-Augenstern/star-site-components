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
package com.xp.starsite.asynccmd;

import static org.assertj.core.api.Assertions.assertThat;

import com.xp.starsite.asynccmd.client.AsyncCommandClient;
import com.xp.starsite.asynccmd.executor.AsyncCommandExecutor;
import com.xp.starsite.asynccmd.model.AsyncCommand;
import com.xp.starsite.asynccmd.scheduler.AsyncCommandScheduler;
import com.xp.starsite.lock.client.LockClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 自动装配测试：memory 形态零依赖完整装配、生命周期由容器托管启停、
 * jdbc 缺 DataSource 快速失败、关开关不装配、owner 默认取应用名。
 *
 * @author muyi
 * @date 2026/10/25
 */
class AsyncCommandAutoConfigurationTest {

    /** 上下文测试运行器：以自动装配方式拉起 AsyncCommandAutoConfiguration 并逐用例断言容器状态 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AsyncCommandAutoConfiguration.class));

    /**
     * 用例：memory 形态零依赖完整装配 + 生命周期容器托管 + 提交到执行全链路。
     * <p>验证点：无 DataSource 时 memory 形态可完整装配（Client/Scheduler/Lifecycle 齐备）；
     * SmartLifecycle 随容器 refresh 自动拉起调度器；提交命令并手动 tick 后执行成功（SUCCESS）。
     */
    @Test
    void memory形态_无DataSource完整装配_生命周期容器托管() {
        runner.withPropertyValues("starsite.async-command.store=memory")
                .withBean("exec", AsyncCommandExecutor.class, () -> new AsyncCommandExecutor() {
                    @Override public String type() { return "demo"; }
                    @Override public void execute(AsyncCommand cmd) { }
                })
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(AsyncCommandClient.class);
                    assertThat(ctx).hasSingleBean(AsyncCommandScheduler.class);
                    assertThat(ctx).hasSingleBean(AsyncCommandLifecycle.class);
                    // SmartLifecycle 由容器启动：refresh 后调度器处于运行态
                    assertThat(ctx.getBean(AsyncCommandScheduler.class).isStarted()).isTrue();

                    AsyncCommandClient client = ctx.getBean(AsyncCommandClient.class);
                    AsyncCommand submitted = client.submit("demo", "k1", "{}");
                    assertThat(submitted.getId()).isPositive();
                    // 手动驱动一次调度循环，验证提交→认领→执行→终态回写全链路
                    ctx.getBean(AsyncCommandScheduler.class).tick();
                    assertThat(client.findById(submitted.getId()).getStatus())
                            .isEqualTo(AsyncCommand.SUCCESS);
                });
    }

    /**
     * 用例：jdbc 形态缺 DataSource 快速失败。
     * <p>验证点：store=jdbc 且容器中无 JdbcTemplate 时装配失败，且异常信息明确提示改用 store=memory。
     */
    @Test
    void jdbc形态_缺DataSource_快速失败提示memory() {
        runner.withPropertyValues("starsite.async-command.store=jdbc")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure()
                        .hasMessageContaining("store=memory"));
    }

    /**
     * 用例：jdbc 形态缺 StarsiteIdGenerator 快速失败。
     * <p>验证点：store=jdbc、JdbcTemplate 在容器中，但未引入 starsite-sequence 组件时装配失败，
     * 异常信息明确提示引入 starsite-sequence-spring-boot-starter（物理主键无来源不可降级）。
     */
    @Test
    void jdbc形态_缺IdGenerator_快速失败提示sequence() {
        // 占位 DataSource：JdbcTemplate 仅需存在（装配在 idGen 检查前不会真正连库）
        runner.withPropertyValues("starsite.async-command.store=jdbc")
                .withBean(org.springframework.jdbc.core.JdbcTemplate.class, () ->
                        new org.springframework.jdbc.core.JdbcTemplate(
                                new org.springframework.jdbc.datasource.SimpleDriverDataSource()))
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure()
                        .hasMessageContaining("StarsiteIdGenerator"));
    }

    /**
     * 用例：总开关关闭时不装配。
     * <p>验证点：enabled=false 时 Client/Scheduler 等核心 Bean 均不创建。
     */
    @Test
    void 关开关_不装配() {
        runner.withPropertyValues(
                        "starsite.async-command.store=memory",
                        "starsite.async-command.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(AsyncCommandClient.class);
                    assertThat(ctx).doesNotHaveBean(AsyncCommandScheduler.class);
                });
    }

    /**
     * 用例：LEADER 模式注入 LockClient Bean 即自动接线调度锁。
     * <p>验证点：容器中有 LockClient（引了 starsite-lock-spring-boot-starter）时，
     * 调度器的调度锁被自动注入——真正选主，无需应用手工 setScheduleLock。
     */
    @Test
    void LEADER模式_有LockClientBean_自动接线调度锁() {
        LockClient stub =
                new LockClient() {
                    @Override public boolean tryLock(String lockName) { return true; }
                    @Override public void unlock(String lockName) { /* no-op */ }
                };
        runner.withPropertyValues(
                        "starsite.async-command.store=memory",
                        "starsite.async-command.schedule-mode=leader")
                .withBean(LockClient.class, () -> stub)
                .run(ctx -> assertThat(ctx.getBean(AsyncCommandScheduler.class).getScheduleLock())
                        .isSameAs(stub));
    }

    /**
     * 用例：LEADER 模式无 LockClient Bean 时优雅退化。
     * <p>验证点：容器中无锁 Bean 时调度器照常装配（调度锁为 null——safeTick 对 null 锁不做门控，
     * 等价 COMPETING），不阻断启动。
     */
    @Test
    void LEADER模式_无LockClientBean_退化装配不阻断() {
        runner.withPropertyValues(
                        "starsite.async-command.store=memory",
                        "starsite.async-command.schedule-mode=leader")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(AsyncCommandScheduler.class);
                    assertThat(ctx.getBean(AsyncCommandScheduler.class).getScheduleLock()).isNull();
                });
    }

    /**
     * 用例：owner 未配置时默认取应用名。
     * <p>验证点：未显式配置 owner 时，提交的命令其 owner 回填为 spring.application.name。
     */
    @Test
    void owner默认取应用名() {
        runner.withPropertyValues(
                        "starsite.async-command.store=memory",
                        "spring.application.name=demo-service")
                .run(ctx -> {
                    AsyncCommandClient client = ctx.getBean(AsyncCommandClient.class);
                    AsyncCommand cmd = client.submit("t", "k", "{}");
                    assertThat(cmd.getOwner()).isEqualTo("demo-service");
                });
    }
}
