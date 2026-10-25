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

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 自动装配测试：memory 形态零依赖完整装配、生命周期由容器托管启停、
 * jdbc 缺 DataSource 快速失败、关开关不装配、owner 默认取应用名。
 *
 * @author muyi
 * @since 2026-10
 */
class AsyncCommandAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AsyncCommandAutoConfiguration.class));

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
                    ctx.getBean(AsyncCommandScheduler.class).tick();
                    assertThat(client.findById(submitted.getId()).getStatus())
                            .isEqualTo(AsyncCommand.SUCCESS);
                });
    }

    @Test
    void jdbc形态_缺DataSource_快速失败提示memory() {
        runner.withPropertyValues("starsite.async-command.store=jdbc")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure()
                        .hasMessageContaining("store=memory"));
    }

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
