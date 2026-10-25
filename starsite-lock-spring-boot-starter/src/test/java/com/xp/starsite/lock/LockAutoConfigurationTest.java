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
package com.xp.starsite.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.xp.starsite.lock.client.LockClient;
import com.xp.starsite.lock.mybatis.ScheduleLockMapper;
import com.xp.starsite.lock.redis.RedisLockClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 锁 starter 自动装配单测：db/redis 双形态选择、关键依赖缺失快速失败、
 * ownerId 缺省回填应用名@pid、总开关让位。
 *
 * @author muyi
 * @date 2026/10/25
 */
class LockAutoConfigurationTest {

    /** 装配跑道（每用例独立容器） */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LockAutoConfiguration.class));

    /** db 模式（默认）缺 Mapper 快速失败，异常信息提示 MapperScan 排查方向 */
    @Test
    void db模式_缺Mapper_快速失败() {
        runner.run(ctx -> assertThat(ctx).hasFailed()
                .getFailure()
                .hasMessageContaining("ScheduleLockMapper"));
    }

    /** db 模式有 Mapper 但缺 sequence 组件的 StarsiteIdGenerator——锁行主键无来源，快速失败 */
    @Test
    void db模式_缺IdGenerator_快速失败() {
        runner.withBean(ScheduleLockMapper.class, () -> mock(ScheduleLockMapper.class))
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure()
                        .hasMessageContaining("StarsiteIdGenerator"));
    }

    /** redis 模式缺 StringRedisTemplate 快速失败，异常信息提示切回 db */
    @Test
    void redis模式_缺Template_快速失败() {
        runner.withPropertyValues("starsite.lock.mode=redis")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure()
                        .hasMessageContaining("StringRedisTemplate"));
    }

    /** redis 模式正常装配：RedisLockClient 形态，ownerId 缺省回填 应用名@pid */
    @Test
    void redis模式_正常装配_owner回填应用名加pid() {
        runner.withPropertyValues("starsite.lock.mode=redis", "spring.application.name=demo-lock")
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(LockClient.class);
                    assertThat(ctx.getBean(LockClient.class)).isInstanceOf(RedisLockClient.class);
                    // ownerId 未显式配置时回填 应用名@pid（多实例持锁主体可区分、可追溯）
                    assertThat(ctx.getBean(LockProperties.class).getOwnerId())
                            .startsWith("demo-lock@");
                });
    }

    /** 总开关关闭：完全不装配（无 DB/Redis 依赖的消费方零感知） */
    @Test
    void 关开关_不装配() {
        runner.withPropertyValues("starsite.lock.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(LockClient.class));
    }
}
