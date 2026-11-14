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
package com.xp.starsite.quota;

import com.xp.starsite.quota.core.QuotaEngine;
import com.xp.starsite.quota.health.QuotaHealthIndicator;
import com.xp.starsite.quota.store.QuotaSql;
import com.xp.starsite.quota.store.QuotaStore;
import com.xp.starsite.quota.store.jdbc.JdbcQuotaStore;
import com.xp.starsite.quota.store.memory.MemoryQuotaStore;
import com.xp.starsite.quota.store.redis.RedisQuotaStore;
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
 * 自动装配（memory/jdbc/redis 路径；MyBatis 路径见 {@link QuotaMyBatisAutoConfiguration}，
 * 其在 imports 中先于本类——已装配 QuotaStore 时本类的存储 Bean 让位）。
 *
 * <p>装配序（全部 @ConditionalOnMissingBean 让位）：SQL 方言 → 存储 → 引擎
 * （QuotaClient 即引擎本体）→ 健康端点。
 *
 * @author muyi
 * @date 2026/11/14
 */
@AutoConfiguration
@EnableConfigurationProperties(QuotaProperties.class)
@ConditionalOnProperty(prefix = "starsite.quota", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class QuotaAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(QuotaAutoConfiguration.class);

    /**
     * 装配 SQL 方言（MySQL 默认；自定义方言让位——TBase PG 等接入通道）。
     *
     * @return MySQL 方言
     */
    @Bean
    @ConditionalOnMissingBean
    public QuotaSql quotaSql() {
        return new QuotaSql.MySql();
    }

    /**
     * 装配配额存储：store=redis 走 Redis（Lua 条件更新）；store=memory 直接内存；
     * store=jdbc 有 DataSource 走 JDBC；store=auto 探测。显式 store=redis 缺
     * StringRedisTemplate → 启动期快速失败并给引导（不静默降级）。
     *
     * @param props        配置
     * @param jdbcTemplate JdbcTemplate（可缺）
     * @param redisTemplate Redis 字符串模板（redis 形态用，可缺——泛型擦除不触发类加载）
     * @param idGenerator  id 发号器（sequence 组件；表形态用，可缺）
     * @param sql          SQL 方言
     * @return 配额存储实例
     */
    @Bean
    @ConditionalOnMissingBean(QuotaStore.class)
    public QuotaStore quotaStore(QuotaProperties props,
                                 ObjectProvider<JdbcTemplate> jdbcTemplate,
                                 ObjectProvider<StringRedisTemplate> redisTemplate,
                                 ObjectProvider<StarsiteIdGenerator> idGenerator,
                                 QuotaSql sql) {
        if (props.getStore() == QuotaProperties.Store.REDIS) {
            StringRedisTemplate redis = redisTemplate.getIfAvailable();
            if (redis == null) {
                throw new IllegalStateException("starsite.quota.store=redis 需要 "
                        + "StringRedisTemplate Bean——引 spring-boot-starter-data-redis"
                        + "（与 lock/idempotent redis 形态同依赖）；或改 store=auto/jdbc/mybatis");
            }
            log.info("[starsite-quota] 存储形态：redis（前缀 {}）", props.getRedisKeyPrefix());
            return new RedisQuotaStore(redis, props.getRedisKeyPrefix());
        }
        JdbcTemplate jdbc = jdbcTemplate.getIfAvailable();
        boolean wantJdbc = props.getStore() == QuotaProperties.Store.JDBC
                || (props.getStore() == QuotaProperties.Store.AUTO && jdbc != null);
        if (wantJdbc && jdbc != null) {
            log.info("[starsite-quota] 存储形态：jdbc（表 {}）", props.getTableName());
            return new JdbcQuotaStore(jdbc, props.getTableName(), sql, true,
                    resolveIds(idGenerator.getIfAvailable()));
        }
        if (wantJdbc) {
            log.warn("[starsite-quota] store=jdbc 但无 DataSource——降级 memory（单机配额）");
        } else {
            log.info("[starsite-quota] 存储形态：memory（单机配额；生产建议 jdbc/mybatis/redis）");
        }
        return new MemoryQuotaStore();
    }

    /**
     * 装配配额引擎（实现 {@link com.xp.starsite.quota.api.QuotaClient} 门面；无后台线程）。
     *
     * @param store 配额存储
     * @return 引擎实例
     */
    @Bean
    @ConditionalOnMissingBean
    public QuotaEngine quotaEngine(QuotaStore store) {
        return new QuotaEngine(store);
    }

    /**
     * 装配健康端点（引 actuator 即有 starsiteQuota：账户数——redis 形态观测降级省略）。
     *
     * @param store 配额存储
     * @return 健康端点实例
     */
    @Bean
    @ConditionalOnMissingBean(name = "quotaHealthIndicator")
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    public QuotaHealthIndicator quotaHealthIndicator(QuotaStore store) {
        return new QuotaHealthIndicator(store);
    }

    /** id 发号：sequence 组件在场用之；缺席降级毫秒基线发号并 warn（memory 形态可接受） */
    private static LongSupplier resolveIds(StarsiteIdGenerator generator) {
        if (generator != null) {
            return generator::next;
        }
        log.warn("[starsite-quota] 容器无 StarsiteIdGenerator——记录 id 降级毫秒基线发号"
                + "（生产请引 starsite-sequence-spring-boot-starter）");
        AtomicLong fallback = new AtomicLong(System.currentTimeMillis() * 1000L);
        return fallback::getAndIncrement;
    }
}
