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

import com.xp.starsite.idempotent.store.IdempotentStore;
import com.xp.starsite.idempotent.store.mybatis.IdempotentRecordMapper;
import com.xp.starsite.idempotent.store.mybatis.MyBatisIdempotentStore;
import com.xp.starsite.sequence.facade.StarsiteIdGenerator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis 路径自动装配（消费方 classpath 自带 mybatis + 显式
 * {@code starsite.idempotent.store=mybatis}；imports 中先于
 * {@link IdempotentAutoConfiguration}——本类装配的 IdempotentStore 让主类的存储 Bean 让位）。
 *
 * <p>Mapper XML 在 core 的同包资源路径（MyBatis 官方约定自动加载，D3 决策）——
 * 消费方 SqlSessionFactory 需为默认 typeAliases/mapperLocations 行为或手工包含本包。
 *
 * @author muyi
 * @date 2026/11/12
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({SqlSessionFactory.class, SqlSessionFactoryBean.class, IdempotentRecordMapper.class})
@ConditionalOnProperty(prefix = "starsite.idempotent", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(IdempotentProperties.class)
public class IdempotentMyBatisAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IdempotentMyBatisAutoConfiguration.class);

    /**
     * 装配 Mapper Bean（从容器 SqlSessionFactory 取代理）。
     * 仅在显式 store=mybatis 时装配——否则主配置的 jdbc/memory 存储不需要 Mapper，
     * 无条件创建会在消费方 MyBatis 配置未加载本包 XML 时炸掉整个启动
     * （schedule 2026-11-06 reminder 生产部署实证：store=auto 也被连坐——同教训预防）。
     *
     * @param sqlSessionFactory SqlSessionFactory（消费方装配）
     * @return Mapper 代理
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "starsite.idempotent", name = "store", havingValue = "mybatis")
    public IdempotentRecordMapper idempotentRecordMapper(SqlSessionFactory sqlSessionFactory) {
        org.mybatis.spring.mapper.MapperFactoryBean<IdempotentRecordMapper> factory =
                new org.mybatis.spring.mapper.MapperFactoryBean<>(IdempotentRecordMapper.class);
        factory.setSqlSessionFactory(sqlSessionFactory);
        try {
            // 必须走 Spring 生命周期：afterPropertiesSet → checkDaoConfig →
            // configuration.addMapper（同时按同包约定自动加载 IdempotentRecordMapper.xml）；
            // 直接 getObject() 会因接口未注册 MapperRegistry 而 BindingException
            factory.afterPropertiesSet();
            return factory.getObject();
        } catch (Exception e) {
            throw new IllegalStateException("IdempotentRecordMapper 装配失败（检查 SqlSessionFactory 的 mapper 装配）", e);
        }
    }

    /**
     * 装配 MyBatis 幂等存储（显式 store=mybatis）。
     *
     * @param mapper      Mapper
     * @param props       配置
     * @param idGenerator id 发号器（sequence 组件）
     * @return 幂等存储实例
     */
    @Bean(name = "idempotentStore")
    @ConditionalOnMissingBean(IdempotentStore.class)
    @ConditionalOnProperty(prefix = "starsite.idempotent", name = "store", havingValue = "mybatis")
    public IdempotentStore myBatisIdempotentStore(IdempotentRecordMapper mapper,
                                                  IdempotentProperties props,
                                                  ObjectProvider<StarsiteIdGenerator> idGenerator) {
        log.info("[starsite-idempotent] 存储形态：mybatis（表 {}）", props.getTableName());
        return new MyBatisIdempotentStore(mapper, props.getTableName(), true,
                resolveIds(idGenerator.getIfAvailable()));
    }

    /** id 发号：sequence 在场用之；缺席降级并 warn（生产请引 sequence starter） */
    private static LongSupplier resolveIds(StarsiteIdGenerator generator) {
        if (generator != null) {
            return generator::next;
        }
        log.warn("[starsite-idempotent] 容器无 StarsiteIdGenerator——记录 id 降级毫秒基线发号");
        AtomicLong fallback = new AtomicLong(System.currentTimeMillis() * 1000L);
        return fallback::getAndIncrement;
    }
}
