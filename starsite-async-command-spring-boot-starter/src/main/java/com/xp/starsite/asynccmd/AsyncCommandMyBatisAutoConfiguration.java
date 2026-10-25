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
package com.xp.starsite.asynccmd;

import com.xp.starsite.asynccmd.mybatis.AsyncCommandMapper;
import com.xp.starsite.asynccmd.mybatis.MyBatisAsyncCommandStore;
import com.xp.starsite.asynccmd.store.AsyncCommandStore;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * MyBatis 适配自动装配：容器中存在 MyBatis 会话工厂（{@code SqlSessionFactory} Bean，
 * 即消费方引了 mybatis-spring-boot-starter）且存储形态为 jdbc 时，
 * 命令存储优先装配 {@link MyBatisAsyncCommandStore}（替换默认的 JdbcTemplate 版）——
 * 消费方的 MyBatis 拦截器/分页插件/SQL 日志对命令表原生生效。
 * <p>
 * 三重不误伤：无 MyBatis 类（classpath）、无会话工厂 Bean（只有依赖没引 starter）、
 * store=memory（内存形态与 SQL 载体无关）时本装配均整体不生效，默认装配兜底。
 * <p>
 * 排序链：@AutoConfigureAfter MybatisAutoConfiguration（会话工厂就绪后再判 @ConditionalOnBean）→
 * @AutoConfigureBefore 默认装配（先注册 MyBatis 版存储，默认装配的 @ConditionalOnMissingBean 让位）。
 *
 * @author muyi
 * @date 2026/10/25
 */
@AutoConfiguration
@AutoConfigureAfter(name = "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration")
@AutoConfigureBefore(AsyncCommandAutoConfiguration.class)
@ConditionalOnClass(SqlSessionFactory.class)
@ConditionalOnBean(SqlSessionFactory.class)
@ConditionalOnProperty(prefix = "starsite.async-command", name = "enabled", havingValue = "true", matchIfMissing = true)
@MapperScan(basePackages = "com.xp.starsite.asynccmd.mybatis", annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class AsyncCommandMyBatisAutoConfiguration {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(AsyncCommandMyBatisAutoConfiguration.class);

    /**
     * 装配 MyBatis 版命令存储 Bean（优先于 JdbcTemplate 版——经 @AutoConfigureBefore 排序）。
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
    @ConditionalOnProperty(prefix = "starsite.async-command", name = "store", havingValue = "jdbc", matchIfMissing = true)
    public AsyncCommandStore myBatisAsyncCommandStore(AsyncCommandMapper mapper,
                                                      AsyncCommandProperties props,
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
