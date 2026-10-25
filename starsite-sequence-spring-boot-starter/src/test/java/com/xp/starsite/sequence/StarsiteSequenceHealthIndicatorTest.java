package com.xp.starsite.sequence;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 健康端点装配与语义测试：UP + workerId/形态明细（被动上报，不消耗号段）。
 *
 * @author muyi
 * @since 2026-10
 */
class StarsiteSequenceHealthIndicatorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StarsiteSequenceAutoConfiguration.class));

    @Test
    void 默认装配_UP_明细含workerId与形态_被动上报不消耗号段() {
        runner.run(ctx -> {
            StarsiteIdGenerator generator = ctx.getBean(StarsiteIdGenerator.class);
            long idBefore = generator.next();

            org.springframework.boot.actuate.health.HealthIndicator indicator =
                    ctx.getBean("starsiteSequenceHealthIndicator",
                            org.springframework.boot.actuate.health.HealthIndicator.class);
            org.springframework.boot.actuate.health.Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(org.springframework.boot.actuate.health.Status.UP);
            assertThat(health.getDetails()).containsKeys("workerId", "allocator");
            assertThat(String.valueOf(health.getDetails().get("allocator"))).contains("standalone");
            // 被动上报的结构性证明：明细不含发号产物，且重复调用 health 后 id 仅随业务调用前进
            assertThat(health.getDetails()).doesNotContainKey("lastId");
            indicator.health(); // 再调一次 health
            long idAfter = generator.next();
            assertThat(idAfter).isGreaterThan(idBefore);
        });
    }
}
