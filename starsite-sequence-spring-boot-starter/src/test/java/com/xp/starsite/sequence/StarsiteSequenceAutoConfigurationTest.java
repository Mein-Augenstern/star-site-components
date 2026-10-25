package com.xp.starsite.sequence;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动装配测试：单机形态零 DataSource 可用；关开关完全不装配；自定义 Bean 让位。
 *
 * @author muyi
 * @since 2026-10
 */
class StarsiteSequenceAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StarsiteSequenceAutoConfiguration.class));

    @Test
    void 默认即单机形态_无DataSource也能用() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(StarsiteIdGenerator.class);
            StarsiteIdGenerator gen = ctx.getBean(StarsiteIdGenerator.class);
            assertThat(gen.getWorkerId()).isEqualTo(1); // 默认 worker-id=1
            assertThat(gen.next()).isPositive();
            assertThat(gen.describe()).contains("standalone");
        });
    }

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

    @Test
    void 关开关_不装配() {
        runner.withPropertyValues("starsite.sequence.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(StarsiteIdGenerator.class));
    }

    @Test
    void mysql形态_缺DataSource_快速失败且提示清晰() {
        runner.withPropertyValues("starsite.sequence.mode=mysql")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure()
                        .hasMessageContaining("mode=mysql 需要 DataSource"));
    }

    @Test
    void 位宽非法_启动拒绝且提示明确() {
        runner.withPropertyValues(
                "starsite.sequence.worker-id-bits=12",
                "starsite.sequence.sequence-bits=12")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure()
                        .hasMessageContaining("合计不超过 22"));
    }

    @Test
    void 应用自定义Bean_装配让位() {
        SnowflakeIdGenerator delegate = new SnowflakeIdGenerator(99, 1767225600000L, 10, 12, 2000,
                SnowflakeIdGenerator.BackwardPolicy.CLAMP);
        runner.withBean("custom", StarsiteIdGenerator.class,
                        () -> new StarsiteIdGenerator(delegate, 99, "custom"))
                .run(ctx -> assertThat(ctx.getBean(StarsiteIdGenerator.class).getWorkerId()).isEqualTo(99));
    }
}
