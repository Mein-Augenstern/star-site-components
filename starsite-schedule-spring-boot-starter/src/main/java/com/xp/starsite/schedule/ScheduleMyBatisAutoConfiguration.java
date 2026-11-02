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

import com.xp.starsite.schedule.mybatis.MyBatisRunLogStore;
import com.xp.starsite.schedule.mybatis.ScheduleRunLogMapper;
import com.xp.starsite.schedule.store.RunLogStore;
import com.xp.starsite.sequence.facade.StarsiteIdGenerator;
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
 * MyBatis 路径自动装配（消费方 classpath 自带 mybatis + 显式
 * {@code starsite.schedule.store=mybatis}；imports 中先于
 * {@link ScheduleAutoConfiguration}——本类装配的 RunLogStore 让主类的存储 Bean 让位）。
 *
 * <p>Mapper XML 在 core 的同包资源路径（MyBatis 官方约定自动加载，D3 决策）——
 * 消费方 SqlSessionFactory 需为默认 typeAliases/mapperLocations 行为或手工包含本包。
 *
 * @author muyi
 * @date 2026/11/02
 */
@AutoConfiguration
@ConditionalOnClass({SqlSessionFactory.class, SqlSessionFactoryBean.class, ScheduleRunLogMapper.class})
@ConditionalOnProperty(prefix = "starsite.schedule", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(ScheduleProperties.class)
public class ScheduleMyBatisAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ScheduleMyBatisAutoConfiguration.class);

    /**
     * 装配 Mapper Bean（从容器 SqlSessionFactory 取代理）。
     *
     * @param sqlSessionFactory SqlSessionFactory（消费方装配）
     * @return Mapper 代理
     */
    @Bean
    @ConditionalOnMissingBean
    public ScheduleRunLogMapper scheduleRunLogMapper(SqlSessionFactory sqlSessionFactory) {
        org.mybatis.spring.mapper.MapperFactoryBean<ScheduleRunLogMapper> factory =
                new org.mybatis.spring.mapper.MapperFactoryBean<>(ScheduleRunLogMapper.class);
        factory.setSqlSessionFactory(sqlSessionFactory);
        try {
            return factory.getObject();
        } catch (Exception e) {
            throw new IllegalStateException("ScheduleRunLogMapper 装配失败（检查 SqlSessionFactory 的 mapper 装配）", e);
        }
    }

    /**
     * 装配 MyBatis 流水存储（store=mybatis 或 auto 且 Mapper 可用）。
     *
     * @param mapper      Mapper
     * @param props       配置
     * @param idGenerator id 发号器（sequence 组件）
     * @return 流水存储实例
     */
    @Bean(name = "runLogStore")
    @ConditionalOnMissingBean(RunLogStore.class)
    @ConditionalOnProperty(prefix = "starsite.schedule", name = "store", havingValue = "mybatis")
    public RunLogStore myBatisRunLogStore(ScheduleRunLogMapper mapper, ScheduleProperties props,
                                          ObjectProvider<StarsiteIdGenerator> idGenerator) {
        log.info("[starsite-schedule] 存储形态：mybatis（表 {}）", props.getTableName());
        return new MyBatisRunLogStore(mapper, props.getTableName(), true,
                resolveIds(idGenerator.getIfAvailable()));
    }

    /** id 发号：sequence 在场用之；缺席降级并 warn（生产请引 sequence starter） */
    private static java.util.function.LongSupplier resolveIds(StarsiteIdGenerator generator) {
        if (generator != null) {
            return generator::next;
        }
        log.warn("[starsite-schedule] 容器无 StarsiteIdGenerator——流水 id 降级毫秒基线发号");
        java.util.concurrent.atomic.AtomicLong fallback =
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() * 1000L);
        return fallback::getAndIncrement;
    }
}
