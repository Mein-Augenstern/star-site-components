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

import com.xp.starsite.quota.store.QuotaStore;
import com.xp.starsite.quota.store.mybatis.MyBatisQuotaStore;
import com.xp.starsite.quota.store.mybatis.QuotaAccountMapper;
import com.xp.starsite.sequence.facade.StarsiteIdGenerator;
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
 * MyBatis 路径自动装配（消费方 classpath 自带 mybatis + 显式
 * {@code starsite.quota.store=mybatis}；imports 中先于
 * {@link QuotaAutoConfiguration}——本类装配的 QuotaStore 让主类的存储 Bean 让位）。
 * Mapper 仅在显式 store=mybatis 时装配（schedule 2026-11-06 连坐教训预防）。
 *
 * @author muyi
 * @date 2026/11/14
 */
@AutoConfiguration
@ConditionalOnClass({SqlSessionFactory.class, SqlSessionFactoryBean.class, QuotaAccountMapper.class})
@ConditionalOnProperty(prefix = "starsite.quota", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(QuotaProperties.class)
public class QuotaMyBatisAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(QuotaMyBatisAutoConfiguration.class);

    /**
     * 装配 Mapper Bean（从容器 SqlSessionFactory 取代理；仅显式 store=mybatis 时）。
     *
     * @param sqlSessionFactory SqlSessionFactory（消费方装配）
     * @return Mapper 代理
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "starsite.quota", name = "store", havingValue = "mybatis")
    public QuotaAccountMapper quotaAccountMapper(SqlSessionFactory sqlSessionFactory) {
        org.mybatis.spring.mapper.MapperFactoryBean<QuotaAccountMapper> factory =
                new org.mybatis.spring.mapper.MapperFactoryBean<>(QuotaAccountMapper.class);
        factory.setSqlSessionFactory(sqlSessionFactory);
        try {
            factory.afterPropertiesSet();
            return factory.getObject();
        } catch (Exception e) {
            throw new IllegalStateException("QuotaAccountMapper 装配失败（检查 SqlSessionFactory 的 mapper 装配）", e);
        }
    }

    /**
     * 装配 MyBatis 配额存储（显式 store=mybatis）。
     *
     * @param mapper      Mapper
     * @param props       配置
     * @param idGenerator id 发号器（sequence 组件）
     * @return 配额存储实例
     */
    @Bean(name = "quotaStore")
    @ConditionalOnMissingBean(QuotaStore.class)
    @ConditionalOnProperty(prefix = "starsite.quota", name = "store", havingValue = "mybatis")
    public QuotaStore myBatisQuotaStore(QuotaAccountMapper mapper, QuotaProperties props,
                                        ObjectProvider<StarsiteIdGenerator> idGenerator) {
        log.info("[starsite-quota] 存储形态：mybatis（表 {}）", props.getTableName());
        return new MyBatisQuotaStore(mapper, props.getTableName(), true,
                resolveIds(idGenerator.getIfAvailable()));
    }

    /** id 发号：sequence 在场用之；缺席降级并 warn */
    private static LongSupplier resolveIds(StarsiteIdGenerator generator) {
        if (generator != null) {
            return generator::next;
        }
        log.warn("[starsite-quota] 容器无 StarsiteIdGenerator——记录 id 降级毫秒基线发号");
        AtomicLong fallback = new AtomicLong(System.currentTimeMillis() * 1000L);
        return fallback::getAndIncrement;
    }
}
