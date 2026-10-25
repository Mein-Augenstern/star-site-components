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
package com.xp.starsite.sequence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 自动装配测试：单机形态零 DataSource 可用；关开关完全不装配；自定义 Bean 让位。
 *
 * @author muyi
 * @date 2026/10/25
 */
class StarsiteSequenceAutoConfigurationTest {

    /** 上下文测试运行器：仅装载被测自动装配，模拟应用引入 starter 后的最小容器 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StarsiteSequenceAutoConfiguration.class));

    /**
     * 验证默认配置（零额外属性、零 DataSource）下开箱即用：
     * 装配出唯一 StarsiteIdGenerator，worker-id 取默认值 1，形态为 standalone，发号返回正数。
     */
    @Test
    void 默认即单机形态_无DataSource也能用() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(StarsiteIdGenerator.class);
            StarsiteIdGenerator gen = ctx.getBean(StarsiteIdGenerator.class);
            // 默认 worker-id=1（core SequenceConfig 的缺省值）
            assertThat(gen.getWorkerId()).isEqualTo(1);
            assertThat(gen.next()).isPositive();
            assertThat(gen.describe()).contains("standalone");
        });
    }

    /**
     * 验证配置项绑定生效：自定义 worker-id=7、worker-id-bits=5、sequence-bits=17 后，
     * 发号结果可按位布局解析——低 17 位为序列（首次发号为 0），第 17~21 位为机器位 7。
     */
    @Test
    void 配置生效_自定义workerId与位宽() {
        runner.withPropertyValues(
                "starsite.sequence.worker-id=7",
                "starsite.sequence.worker-id-bits=5",
                "starsite.sequence.sequence-bits=17")
                .run(ctx -> {
                    StarsiteIdGenerator gen = ctx.getBean(StarsiteIdGenerator.class);
                    assertThat(gen.getWorkerId()).isEqualTo(7);
                    long id = gen.next();
                    // 17 位序列：id 低 17 位 = 序列 0，机器位 7 落在 17~22 位
                    assertThat(id & 0x1FFFF).isZero();
                    assertThat((id >>> 17) & 0x1F).isEqualTo(7);
                });
    }

    /** 验证总开关语义：starsite.sequence.enabled=false 时完全不装配，容器中不存在 StarsiteIdGenerator Bean。 */
    @Test
    void 关开关_不装配() {
        runner.withPropertyValues("starsite.sequence.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(StarsiteIdGenerator.class));
    }

    /** 验证 mysql 形态快速失败：缺 DataSource 时容器启动失败，异常消息明确提示「mode=mysql 需要 DataSource」。 */
    @Test
    void mysql形态_缺DataSource_快速失败且提示清晰() {
        runner.withPropertyValues("starsite.sequence.mode=mysql")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure()
                        .hasMessageContaining("mode=mysql 需要 DataSource"));
    }

    /** 验证位宽校验 fail-fast：worker-id-bits=12、sequence-bits=12 合计超 22 时启动拒绝，消息含「合计不超过 22」。 */
    @Test
    void 位宽非法_启动拒绝且提示明确() {
        runner.withPropertyValues(
                "starsite.sequence.worker-id-bits=12",
                "starsite.sequence.sequence-bits=12")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure()
                        .hasMessageContaining("合计不超过 22"));
    }

    /** 验证让位语义：应用自备 StarsiteIdGenerator Bean（worker-id=99）时自动装配让位，容器取到的是自定义实例。 */
    @Test
    void 应用自定义Bean_装配让位() {
        SnowflakeIdGenerator delegate = new SnowflakeIdGenerator(99, 1767225600000L, 10, 12, 2000,
                SnowflakeIdGenerator.BackwardPolicy.CLAMP);
        runner.withBean("custom", StarsiteIdGenerator.class,
                        () -> new StarsiteIdGenerator(delegate, 99, "custom"))
                .run(ctx -> assertThat(ctx.getBean(StarsiteIdGenerator.class).getWorkerId()).isEqualTo(99));
    }
}
