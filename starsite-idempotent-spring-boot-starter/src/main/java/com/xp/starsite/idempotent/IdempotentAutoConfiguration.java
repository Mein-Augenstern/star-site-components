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

import com.xp.starsite.idempotent.api.IdempotentClient;
import com.xp.starsite.idempotent.clock.TimeSource;
import com.xp.starsite.idempotent.core.IdempotentEngine;
import com.xp.starsite.idempotent.health.IdempotentHealthIndicator;
import com.xp.starsite.idempotent.jdbc.JdbcIdempotentStore;
import com.xp.starsite.idempotent.memory.MemoryIdempotentStore;
import com.xp.starsite.idempotent.redis.RedisIdempotentStore;
import com.xp.starsite.idempotent.store.IdempotentSql;
import com.xp.starsite.idempotent.store.IdempotentStore;
import com.xp.starsite.sequence.facade.StarsiteIdGenerator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 自动装配（memory/jdbc 路径；MyBatis 路径见 {@link IdempotentMyBatisAutoConfiguration}，
 * 其在 imports 中先于本类——已装配 IdempotentStore 时本类的存储 Bean 让位）。
 *
 * <p>装配序（全部 @ConditionalOnMissingBean 让位）：SQL 方言 → 存储 → 引擎
 * （IdempotentClient 即引擎本体）→ 注解代理 BPP → retention 看护 → 健康端点。
 *
 * @author muyi
 * @date 2026/11/12
 */
@AutoConfiguration
@EnableConfigurationProperties(IdempotentProperties.class)
@ConditionalOnProperty(prefix = "starsite.idempotent", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class IdempotentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IdempotentAutoConfiguration.class);

    /**
     * 装配 SQL 方言（MySQL 默认；自定义方言让位）。
     *
     * @return MySQL 方言
     */
    @Bean
    @ConditionalOnMissingBean
    public IdempotentSql idempotentSql() {
        return new IdempotentSql.MySql();
    }

    /**
     * 装配幂等存储：store=redis 走 Redis（Lua 原生四操作 + TTL 键记忆窗口）；
     * store=memory 直接内存；store=jdbc 有 DataSource 走 JDBC；store=auto 探测
     * （MyBatis 显式配置见 MyBatis 装配，有 DataSource 走 JDBC，否则内存降级）。
     * <p>显式 store=redis 但容器无 StringRedisTemplate → 启动期快速失败并给引导
     * （不静默降级 memory——显式配置错了就该报错，lock 的 fail-fast 同款处置）。
     *
     * @param props        配置
     * @param jdbcTemplate JdbcTemplate（消费方 DataSource 的包装，可缺）
     * @param redisTemplate Redis 字符串模板（redis 形态用，可缺——泛型擦除不触发类加载）
     * @param idGenerator  id 发号器（sequence 组件；memory/jdbc 形态用，可缺）
     * @param sql          SQL 方言
     * @return 幂等存储实例
     */
    @Bean
    @ConditionalOnMissingBean(IdempotentStore.class)
    public IdempotentStore idempotentStore(IdempotentProperties props,
                                           ObjectProvider<JdbcTemplate> jdbcTemplate,
                                           ObjectProvider<StringRedisTemplate> redisTemplate,
                                           ObjectProvider<StarsiteIdGenerator> idGenerator,
                                           IdempotentSql sql) {
        if (props.getStore() == IdempotentProperties.Store.REDIS) {
            StringRedisTemplate redis = redisTemplate.getIfAvailable();
            if (redis == null) {
                throw new IllegalStateException("starsite.idempotent.store=redis 需要 "
                        + "StringRedisTemplate Bean——引 spring-boot-starter-data-redis"
                        + "（与 lock redis 形态同依赖）；或改 store=auto/jdbc/mybatis");
            }
            log.info("[starsite-idempotent] 存储形态：redis（前缀 {}，TTL 即键记忆窗口）",
                    props.getRedisKeyPrefix());
            return new RedisIdempotentStore(redis, props.getRedisKeyPrefix());
        }
        JdbcTemplate jdbc = jdbcTemplate.getIfAvailable();
        boolean wantJdbc = props.getStore() == IdempotentProperties.Store.JDBC
                || (props.getStore() == IdempotentProperties.Store.AUTO && jdbc != null);
        if (wantJdbc && jdbc != null) {
            LongSupplier ids = resolveIds(idGenerator.getIfAvailable());
            log.info("[starsite-idempotent] 存储形态：jdbc（表 {}）", props.getTableName());
            return new JdbcIdempotentStore(jdbc, props.getTableName(), sql, true, ids);
        }
        if (wantJdbc) {
            log.warn("[starsite-idempotent] store=jdbc 但无 DataSource——降级 memory（单机幂等）");
        } else {
            log.info("[starsite-idempotent] 存储形态：memory（单机幂等；生产建议 jdbc/mybatis/redis）");
        }
        return new MemoryIdempotentStore(resolveIds(idGenerator.getIfAvailable()));
    }

    /**
     * 装配幂等引擎（实现 {@link IdempotentClient} 门面；无后台线程——看护另装配）。
     *
     * @param store  幂等存储
     * @param props  配置
     * @return 引擎实例
     */
    @Bean
    @ConditionalOnMissingBean
    public IdempotentEngine idempotentEngine(IdempotentStore store, IdempotentProperties props) {
        return new IdempotentEngine(store, TimeSource.system(), props.keyTtl(),
                props.waitTimeout(), props.getPollMillis(), props.processingStale());
    }

    /**
     * 装配注解代理 BPP（static：BPP 须早于普通 Bean 创建）。
     *
     * @param clientProvider 幂等门面惰性提供器（BPP 创建期过早，代理创建时解析）
     * @param environment    环境抽象（注解属性 ${...} 占位符解析）
     * @return BPP 实例
     */
    @Bean
    public static IdempotentBeanPostProcessor idempotentBeanPostProcessor(
            ObjectProvider<IdempotentClient> clientProvider,
            org.springframework.core.env.Environment environment) {
        return new IdempotentBeanPostProcessor(clientProvider, environment);
    }

    /**
     * 装配过期清理看护（默认开——幂等记录是瞬态去重状态，expire_at 过期即死行；
     * 关闭走 retention-enabled=false）。
     *
     * @param client 幂等门面
     * @param props  配置
     * @return 看护实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "starsite.idempotent", name = "retention-enabled",
            havingValue = "true", matchIfMissing = true)
    public IdempotentRetentionLifecycle idempotentRetentionLifecycle(IdempotentClient client,
                                                                     IdempotentProperties props) {
        return new IdempotentRetentionLifecycle(client, props.purgeInterval(),
                props.getRetentionBatchSize());
    }

    /**
     * 装配健康端点（引 actuator 即有 starsiteIdempotent；三态计数，FAILED 不判 DOWN——
     * 失败是可重试业务自然态，与 async-command 死信语义不同）。
     *
     * @param store 幂等存储
     * @return 健康端点实例
     */
    @Bean
    @ConditionalOnMissingBean(name = "idempotentHealthIndicator")
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    public IdempotentHealthIndicator idempotentHealthIndicator(IdempotentStore store) {
        return new IdempotentHealthIndicator(store);
    }

    /** id 发号：sequence 组件在场用之；缺席降级毫秒基线发号并 warn（memory 形态可接受） */
    private static LongSupplier resolveIds(StarsiteIdGenerator generator) {
        if (generator != null) {
            return generator::next;
        }
        log.warn("[starsite-idempotent] 容器无 StarsiteIdGenerator——记录 id 降级毫秒基线发号"
                + "（生产请引 starsite-sequence-spring-boot-starter）");
        AtomicLong fallback = new AtomicLong(System.currentTimeMillis() * 1000L);
        return fallback::getAndIncrement;
    }
}
