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
package com.xp.starsite.asynccmd.mybatis;

import com.xp.starsite.asynccmd.store.AsyncCommandStore;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * MyBatis 适配自动装配：classpath 存在 MyBatis（{@code SqlSessionFactory}）且存储形态为 jdbc 时，
 * 命令存储优先装配 {@link MyBatisAsyncCommandStore}（替换默认的 JdbcTemplate 版）——
 * 消费方的 MyBatis 拦截器/分页插件/SQL 日志对命令表原生生效。
 * <p>
 * 让位语义：应用自定义 AsyncCommandStore Bean 时完全让位；
 * 无 MyBatis 的消费方零感知（@ConditionalOnClass 不命中即跳过）。
 *
 * @author muyi
 * @date 2026/10/25
 */
@AutoConfiguration
@ConditionalOnClass(SqlSessionFactory.class)
@ConditionalOnProperty(prefix = "starsite.async-command", name = "enabled", havingValue = "true", matchIfMissing = true)
@MapperScan(basePackages = "com.xp.starsite.asynccmd.mybatis", annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class AsyncCommandMyBatisAutoConfiguration {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(AsyncCommandMyBatisAutoConfiguration.class);

    /**
     * 装配 MyBatis 版命令存储 Bean（优先于 JdbcTemplate 版——本装配经
     * AutoConfiguration.imports 排序在默认装配之前执行）。
     * <p>让位语义：@ConditionalOnMissingBean——应用已自定义存储时让位。
     *
     * @param mapper MyBatis Mapper（@MapperScan 注册）
     * @param props  组件配置（读表名与 init-ddl）
     * @param idGenProvider id 生成器提供者（组件表物理主键标准来源，缺失快速失败）
     * @return MyBatis 版存储
     * @throws IllegalStateException 容器中无 StarsiteIdGenerator（未引入 sequence 组件）时
     */
    @Bean
    @ConditionalOnMissingBean(AsyncCommandStore.class)
    public AsyncCommandStore myBatisAsyncCommandStore(AsyncCommandMapper mapper,
                                                      com.xp.starsite.asynccmd.AsyncCommandProperties props,
                                                      ObjectProvider<com.xp.starsite.sequence.facade.StarsiteIdGenerator> idGenProvider) {
        com.xp.starsite.sequence.facade.StarsiteIdGenerator idGen = idGenProvider.getIfAvailable();
        if (idGen == null) {
            throw new IllegalStateException("MyBatis 存储需要 starsite-sequence 组件提供 StarsiteIdGenerator"
                    + "（组件表物理主键标准来源）；请引 starsite-sequence-spring-boot-starter");
        }
        log.info("[starsite-async-command] 存储形态：mybatis（table={}，消费方 MyBatis 生态原生生效）",
                props.getTableName());
        return new MyBatisAsyncCommandStore(mapper, props.getTableName(), props.isInitDdl(), idGen::next);
    }
}
