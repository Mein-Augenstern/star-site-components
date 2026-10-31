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

import com.xp.starsite.lock.client.LockAdminClient;
import com.xp.starsite.lock.client.LockClient;
import com.xp.starsite.lock.mybatis.ScheduleLockMapper;
import com.xp.starsite.lock.store.DbLockAdminClient;
import com.xp.starsite.lock.store.DbLockClient;
import com.xp.starsite.lock.store.LockTableProvisioner;
import com.xp.starsite.sequence.facade.StarsiteIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 分布式锁自动装配：引 jar + 配置即用。
 * <pre>
 * DB 模式（默认）：starsite.lock.mode=mysql（需 MyBatis 环境 + DataSource）
 * Redis 模式：starsite.lock.mode=redis（需 StringRedisTemplate）
 * </pre>
 * 应用自定义 {@link LockClient} Bean 时让位（@ConditionalOnMissingBean）。
 *
 * @author muyi
 * @date 2026/10/25
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "starsite.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LockProperties.class)
public class LockAutoConfiguration {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(LockAutoConfiguration.class);

    /**
     * 装配 LockClient 门面 Bean：按 mode 分发到 DB（MyBatis Mapper）或 Redis 实现。
     * <p>让位语义：@ConditionalOnMissingBean——应用自定义 LockClient 时让位。
     *
     * @param props                 锁配置
     * @param mapperProvider        DB 模式的 Mapper 提供者（@MapperScan 注册）
     * @param redisTemplateProvider Redis 模式的模板提供者
     * @param idGenProvider         id 生成器提供者（DB 模式必填——锁行物理主键标准来源）
     * @param env                   Spring 环境（回填 ownerId）
     * @return LockClient 门面
     * @throws IllegalStateException db 模式缺 Mapper 或缺 StarsiteIdGenerator、redis 模式缺模板时快速失败
     */
    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "starsite.lock", name = "mode", havingValue = "mysql", matchIfMissing = true)
    public LockClient lockClient(LockProperties props,
                                 ObjectProvider<ScheduleLockMapper> mapperProvider,
                                 ObjectProvider<StarsiteIdGenerator> idGenProvider,
                                 Environment env) {
        if (props.getOwnerId() == null || props.getOwnerId().isBlank()) {
            String appName = env.getProperty("spring.application.name", "unknown");
            props.setOwnerId(appName + "@" + ProcessHandle.current().pid());
        }
        // mode=redis 时本 Bean 不装配（ConditionalOnProperty 不命中）——由嵌套 RedisModeConfig 接管。
        // 主配置类不引用 Redis 类型，无 Redis classpath 时 Spring 不解析 Redis 签名（压测实战修复 P1）。
        LockClient rawClient = buildDbClient(props, mapperProvider, idGenProvider, env);
        return maybeWrapWatchdog(props, rawClient);
    }

    /**
     * DB 模式构建原始锁客户端。
     */
    private LockClient buildDbClient(LockProperties props,
                                      ObjectProvider<ScheduleLockMapper> mapperProvider,
                                      ObjectProvider<StarsiteIdGenerator> idGenProvider,
                                      Environment env) {
        ScheduleLockMapper mapper = mapperProvider.getIfAvailable();
        if (mapper == null) {
            throw new IllegalStateException(
                    "starsite.lock.mode=mysql 需要 ScheduleLockMapper Bean（"
                            + "消费方需有 mybatis-spring-boot-starter + DataSource，"
                            + "或配置 @MapperScan 包含 com.xp.starsite.lock.mybatis）");
        }
        StarsiteIdGenerator idGen = idGenProvider.getIfAvailable();
        if (idGen == null) {
            throw new IllegalStateException(
                    "starsite.lock.mode=mysql 需要 StarsiteIdGenerator"
                            + "（starsite-sequence 组件）生成锁行物理主键；请引入"
                            + " starsite-sequence-spring-boot-starter");
        }
        LockTableProvisioner provisioner =
                new LockTableProvisioner(mapper, props.getTableName(), idGen::next);
        if (props.isInitDdl()) {
            provisioner.ensureTable();
        }
        log.info("[starsite-lock] 存储形态：mysql（table={}，owner={}）",
                props.getTableName(), props.getOwnerId());
        return new DbLockClient(mapper, provisioner, props.getTableName(),
                props.getOwnerId(), props.getTtlMs());
    }

    /**
     * 可选取看门狗包装。
     */
    private LockClient maybeWrapWatchdog(LockProperties props, LockClient raw) {
        if (props.isWatchdogEnabled()) {
            log.info("[starsite-lock] 看门狗已启用：每 {}ms 自动续期", Math.max(props.getTtlMs() / 3, 1000));
            return new com.xp.starsite.lock.client.WatchdogLockClient(raw, props.getTtlMs());
        }
        return raw;
    }

    /**
     * Redis 模式装配（嵌套配置类）：仅 classpath 存在 StringRedisTemplate 时加载——
     * 主配置类不引用 Redis 类型，无 Redis 消费方零感知（压测实战修复 P1）。
     */
    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.autoconfigure.condition.ConditionalOnClass(StringRedisTemplate.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "starsite.lock", name = "mode", havingValue = "redis")
    static class RedisModeConfig {

        /** 日志 */
        private static final Logger log = LoggerFactory.getLogger(RedisModeConfig.class);

        /**
         * 装配 Redis 版 LockClient。
         */
        @org.springframework.context.annotation.Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(LockClient.class)
        public LockClient redisLockClient(LockProperties props,
                                          StringRedisTemplate redisTemplate,
                                          Environment env) {
            if (props.getOwnerId() == null || props.getOwnerId().isBlank()) {
                String appName = env.getProperty("spring.application.name", "unknown");
                props.setOwnerId(appName + "@" + ProcessHandle.current().pid());
            }
            log.info("[starsite-lock] 存储形态：redis（prefix={}，owner={}）",
                    props.getRedisKeyPrefix(), props.getOwnerId());
            return new com.xp.starsite.lock.redis.RedisLockClient(redisTemplate, props);
        }
    }

    /**
     * 装配锁运维门面 Bean（可选，{@code starsite.lock.admin-enabled=true} 默认关）：
     * 与 LockClient 的正式互斥语义完全隔离——强制释放卡死锁/查持有者/巡检。
     * 仅 db 模式支持（redis 模式的运维经 redis-cli 直接操作）。
     * <p>让位语义：@ConditionalOnMissingBean——应用已自定义 LockAdminClient 时让位。
     */
    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "starsite.lock", name = "admin-enabled", havingValue = "true")
    public LockAdminClient lockAdminClient(
            LockProperties props,
            ObjectProvider<ScheduleLockMapper> mapperProvider) {
        if (props.getMode() != com.xp.starsite.lock.LockProperties.Mode.MYSQL) {
            log.warn("[starsite-lock] admin-enabled=true 仅支持 db 模式（当前 {}）——运维 Bean 不装配",
                    props.getMode());
            return null;
        }
        ScheduleLockMapper mapper = mapperProvider.getIfAvailable();
        if (mapper == null) {
            throw new IllegalStateException(
                    "starsite.lock.admin-enabled=true 需要 ScheduleLockMapper Bean（同 db 模式前置条件）");
        }
        log.info("[starsite-lock] 运维门面就绪：强制释放/巡检/诊断（table={}）", props.getTableName());
        return new DbLockAdminClient(mapper, props.getTableName());
    }
}
