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

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 锁组件 MyBatis 装配：容器中存在 MyBatis 会话工厂（消费方引了 mybatis-spring-boot-starter）时，
 * 自动扫描注册锁表 Mapper——"引 jar + 配置即用"，消费方无需自行 @MapperScan 组件包。
 * <p>
 * 无 MyBatis 的消费方零感知（@ConditionalOnClass 不命中即跳过，@MapperScan 不被求值）；
 * redis 模式消费方同样零感知（本类只注册 Mapper，锁形态选择在 {@link LockAutoConfiguration}）。
 *
 * @author muyi
 * @date 2026/10/25
 */
@AutoConfiguration
@AutoConfigureAfter(name = "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration")
@ConditionalOnClass(SqlSessionFactory.class)
@ConditionalOnBean(SqlSessionFactory.class)
@ConditionalOnProperty(prefix = "starsite.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
@MapperScan(basePackages = "com.xp.starsite.lock.mybatis", annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class LockMyBatisAutoConfiguration {
    // 纯扫描注册：无 Bean 方法——锁客户端装配在 LockAutoConfiguration.lockClient
}
