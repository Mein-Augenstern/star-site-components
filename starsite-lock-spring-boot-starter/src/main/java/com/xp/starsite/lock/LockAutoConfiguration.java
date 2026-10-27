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

import com.xp.starsite.lock.client.LockClient;
import com.xp.starsite.lock.mybatis.ScheduleLockMapper;
import com.xp.starsite.lock.redis.RedisLockClient;
import com.xp.starsite.lock.store.DbLockClient;
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
 * DB 模式（默认）：starsite.lock.mode=db（需 MyBatis 环境 + DataSource）
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
    public LockClient lockClient(LockProperties props,
                                 ObjectProvider<ScheduleLockMapper> mapperProvider,
                                 ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                 ObjectProvider<com.xp.starsite.sequence.facade.StarsiteIdGenerator> idGenProvider,
                                 Environment env) {
        if (props.getOwnerId() == null || props.getOwnerId().isBlank()) {
            String appName = env.getProperty("spring.application.name", "unknown");
            props.setOwnerId(appName + "@" + ProcessHandle.current().pid());
        }
        return switch (props.getMode()) {
            case DB -> {
                ScheduleLockMapper mapper = mapperProvider.getIfAvailable();
                if (mapper == null) {
                    throw new IllegalStateException(
                            "starsite.lock.mode=db 需要 ScheduleLockMapper Bean（"
                                    + "消费方需有 mybatis-spring-boot-starter + DataSource，"
                                    + "或配置 @MapperScan 包含 com.xp.starsite.lock.mybatis）");
                }
                // 组件表规范：物理主键一律由 sequence 组件生成（无 AUTO_INCREMENT）
                com.xp.starsite.sequence.facade.StarsiteIdGenerator idGen = idGenProvider.getIfAvailable();
                if (idGen == null) {
                    throw new IllegalStateException(
                            "starsite.lock.mode=db 需要 StarsiteIdGenerator"
                                    + "（starsite-sequence 组件）生成锁行物理主键；请引入"
                                    + " starsite-sequence-spring-boot-starter");
                }
                // 存储就绪器（建表/播种）与锁客户端（互斥语义）职责分离，共享同一表名
                com.xp.starsite.lock.store.LockTableProvisioner provisioner =
                        new com.xp.starsite.lock.store.LockTableProvisioner(
                                mapper, props.getTableName(), idGen::next);
                if (props.isInitDdl()) {
                    provisioner.ensureTable();
                }
                log.info("[starsite-lock] 存储形态：db（table={}，owner={}）",
                        props.getTableName(), props.getOwnerId());
                yield new DbLockClient(mapper, provisioner, props.getTableName(),
                        props.getOwnerId(), props.getTtlMs());
            }
            case REDIS -> {
                StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
                if (redis == null) {
                    throw new IllegalStateException(
                            "starsite.lock.mode=redis 需要 StringRedisTemplate Bean；"
                                    + "无 Redis 场景请配置 starsite.lock.mode=db");
                }
                log.info("[starsite-lock] 存储形态：redis（prefix={}，owner={}）",
                        props.getRedisKeyPrefix(), props.getOwnerId());
                yield new RedisLockClient(redis, props);
            }
        };
    }
}
