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
package com.xp.starsite.tuner;

import com.xp.starsite.sequence.facade.StarsiteIdGenerator;
import com.xp.starsite.tuner.store.TunerStore;
import com.xp.starsite.tuner.store.mybatis.MyBatisTunerStore;
import com.xp.starsite.tuner.store.mybatis.TunerMapper;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * MyBatis 路径自动装配（显式 {@code starsite.tuner.store=mybatis}；imports 中先于
 * {@link TunerAutoConfiguration}）。Mapper 仅显式 store=mybatis 时装配（schedule
 * 2026-11-06 连坐教训预防）。
 *
 * @author muyi
 * @date 2026/11/14
 */
@AutoConfiguration
@ConditionalOnClass({SqlSessionFactory.class, SqlSessionFactoryBean.class, TunerMapper.class})
@ConditionalOnProperty(prefix = "starsite.tuner", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(TunerProperties.class)
public class TunerMyBatisAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TunerMyBatisAutoConfiguration.class);

    /**
     * 装配 Mapper Bean（仅显式 store=mybatis 时）。
     *
     * @param sqlSessionFactory SqlSessionFactory（消费方装配）
     * @return Mapper 代理
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "starsite.tuner", name = "store", havingValue = "mybatis")
    public TunerMapper tunerMapper(SqlSessionFactory sqlSessionFactory) {
        org.mybatis.spring.mapper.MapperFactoryBean<TunerMapper> factory =
                new org.mybatis.spring.mapper.MapperFactoryBean<>(TunerMapper.class);
        factory.setSqlSessionFactory(sqlSessionFactory);
        try {
            factory.afterPropertiesSet();
            return factory.getObject();
        } catch (Exception e) {
            throw new IllegalStateException("TunerMapper 装配失败（检查 SqlSessionFactory 的 mapper 装配）", e);
        }
    }

    /**
     * 装配 MyBatis 治理存储。
     *
     * @param mapper      Mapper
     * @param props       配置
     * @param idGenerator id 发号器
     * @return 治理存储
     */
    @Bean(name = "tunerStore")
    @ConditionalOnMissingBean(TunerStore.class)
    @ConditionalOnProperty(prefix = "starsite.tuner", name = "store", havingValue = "mybatis")
    public TunerStore myBatisTunerStore(TunerMapper mapper, TunerProperties props,
                                        ObjectProvider<StarsiteIdGenerator> idGenerator) {
        log.info("[starsite-tuner] 存储形态：mybatis（表前缀 {}）", props.getTablePrefix());
        return new MyBatisTunerStore(mapper, props.getTablePrefix(), true,
                resolveIds(idGenerator.getIfAvailable()),
                com.xp.starsite.tuner.clock.TimeSource.system());
    }

    /** id 发号：sequence 在场用之；缺席降级并 warn（家族同形） */
    private static LongSupplier resolveIds(StarsiteIdGenerator generator) {
        if (generator != null) {
            return generator::next;
        }
        log.warn("[starsite-tuner] 容器无 StarsiteIdGenerator——治理 id 降级毫秒基线发号");
        AtomicLong fallback = new AtomicLong(System.currentTimeMillis() * 1000L);
        return fallback::getAndIncrement;
    }
}
