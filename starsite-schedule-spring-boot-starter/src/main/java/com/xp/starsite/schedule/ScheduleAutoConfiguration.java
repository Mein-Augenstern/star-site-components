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

import com.xp.starsite.lock.client.LockClient;
import com.xp.starsite.schedule.core.ExactlyOnceRunner;
import com.xp.starsite.schedule.core.ScheduleEngine;
import com.xp.starsite.schedule.core.ScheduleTaskRegistry;
import com.xp.starsite.schedule.store.JdbcRunLogStore;
import com.xp.starsite.schedule.store.MemoryRunLogStore;
import com.xp.starsite.schedule.store.RunLogStore;
import com.xp.starsite.schedule.store.ScheduleRunLogSql;
import com.xp.starsite.sequence.facade.StarsiteIdGenerator;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 自动装配（memory/jdbc 路径；MyBatis 路径见 {@link ScheduleMyBatisAutoConfiguration}，
 * 其在 imports 中先于本类——已装配 RunLogStore 时本类的存储 Bean 让位）。
 *
 * <p>装配序（全部 @ConditionalOnMissingBean 让位）：注册表 → 流水存储 → 恰一执行器
 * → 引擎（ScheduleClient 即引擎本体）→ 注解扫描 BPP → SmartLifecycle。
 *
 * @author muyi
 * @date 2026/11/02
 */
@AutoConfiguration
@EnableConfigurationProperties(ScheduleProperties.class)
@ConditionalOnProperty(prefix = "starsite.schedule", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class ScheduleAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ScheduleAutoConfiguration.class);

    /**
     * 装配任务注册表。
     *
     * @return 注册表实例
     */
    @Bean
    @ConditionalOnMissingBean
    public ScheduleTaskRegistry scheduleTaskRegistry() {
        return new ScheduleTaskRegistry();
    }

    /**
     * 装配 SQL 方言（MySQL 默认；自定义方言让位）。
     *
     * @return MySQL 方言
     */
    @Bean
    @ConditionalOnMissingBean
    public ScheduleRunLogSql scheduleRunLogSql() {
        return new ScheduleRunLogSql.MySql();
    }

    /**
     * 装配流水存储：store=memory 直接内存；store=jdbc 有 DataSource 走 JDBC；
     * store=auto 探测（MyBatis 已装配则让位见类注，有 DataSource 走 JDBC，否则内存降级）。
     *
     * @param props           配置
     * @param jdbcTemplate    JdbcTemplate（消费方 DataSource 的包装，可缺）
     * @param idGenerator     id 发号器（sequence 组件；memory 形态可缺）
     * @return 流水存储实例
     */
    @Bean
    @ConditionalOnMissingBean(RunLogStore.class)
    public RunLogStore runLogStore(ScheduleProperties props,
                                   ObjectProvider<JdbcTemplate> jdbcTemplate,
                                   ObjectProvider<StarsiteIdGenerator> idGenerator,
                                   ScheduleRunLogSql sql) {
        JdbcTemplate jdbc = jdbcTemplate.getIfAvailable();
        boolean wantJdbc = props.getStore() == ScheduleProperties.Store.JDBC
                || (props.getStore() == ScheduleProperties.Store.AUTO && jdbc != null);
        if (wantJdbc && jdbc != null) {
            LongSupplier ids = resolveIds(idGenerator.getIfAvailable());
            log.info("[starsite-schedule] 存储形态：jdbc（表 {}）", props.getTableName());
            return new JdbcRunLogStore(jdbc, props.getTableName(), sql, true, ids);
        }
        if (wantJdbc) {
            log.warn("[starsite-schedule] store=jdbc 但无 DataSource——降级 memory（单机观测）");
        } else {
            log.info("[starsite-schedule] 存储形态：memory（单机观测；生产建议 jdbc/mybatis）");
        }
        return new MemoryRunLogStore();
    }

    /**
     * 装配恰一执行器。
     *
     * @param lockClient 分布式锁（lock starter 装配；可 null——guard 任务在 BPP 校验拦截）
     * @param store      流水存储
     * @return 执行器实例
     */
    @Bean
    @ConditionalOnMissingBean
    public ExactlyOnceRunner exactlyOnceRunner(ObjectProvider<LockClient> lockClient, RunLogStore store) {
        return new ExactlyOnceRunner(lockClient.getIfAvailable(), store,
                com.xp.starsite.schedule.clock.TimeSource.system());
    }

    /**
     * 装配调度引擎（实现 {@link com.xp.starsite.schedule.api.ScheduleClient} 门面）。
     * 只创建不启动——start/stop 由 {@link ScheduleLifecycle} 托管。
     *
     * @param registry 注册表
     * @param runner   执行器
     * @param store    流水存储
     * @param props    配置
     * @return 引擎实例
     */
    @Bean
    @ConditionalOnMissingBean
    public ScheduleEngine scheduleEngine(ScheduleTaskRegistry registry, ExactlyOnceRunner runner,
                                         RunLogStore store, ScheduleProperties props) {
        return new ScheduleEngine(registry, runner, store,
                com.xp.starsite.schedule.clock.TimeSource.system(),
                Math.max(50, props.getTickMillis()), Math.max(1, props.getPoolSize()),
                props.staleThreshold());
    }

    /**
     * 装配注解扫描 BPP（static：BPP 须早于普通 Bean 创建）。
     *
     * @param registry           注册表
     * @param lockClientProvider 锁惰性提供器（guard 任务注册时解析——BPP 创建期过早）
     * @param environment        环境抽象（注解属性 ${...} 占位符解析）
     * @return BPP 实例
     */
    @Bean
    public static StarsiteScheduledBeanPostProcessor starsiteScheduledBeanPostProcessor(
            ScheduleTaskRegistry registry, ObjectProvider<LockClient> lockClientProvider,
            org.springframework.core.env.Environment environment) {
        return new StarsiteScheduledBeanPostProcessor(registry, lockClientProvider, environment);
    }

    /**
     * 装配生命周期适配。
     *
     * @param engine 引擎
     * @return 生命周期实例
     */
    @Bean
    @ConditionalOnMissingBean
    public ScheduleLifecycle scheduleLifecycle(ScheduleEngine engine) {
        return new ScheduleLifecycle(engine);
    }

    /** id 发号：sequence 组件在场用之；缺席降级毫秒基线发号并 warn（memory 形态可接受） */
    private static LongSupplier resolveIds(StarsiteIdGenerator generator) {
        if (generator != null) {
            return generator::next;
        }
        log.warn("[starsite-schedule] 容器无 StarsiteIdGenerator——流水 id 降级毫秒基线发号"
                + "（生产请引 starsite-sequence-spring-boot-starter）");
        java.util.concurrent.atomic.AtomicLong fallback =
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() * 1000L);
        return fallback::getAndIncrement;
    }
}
