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
import com.xp.starsite.tuner.clock.TimeSource;
import com.xp.starsite.tuner.core.TunerEngine;
import com.xp.starsite.tuner.health.TunerHealthIndicator;
import com.xp.starsite.tuner.store.TunerSql;
import com.xp.starsite.tuner.store.TunerStore;
import com.xp.starsite.tuner.store.jdbc.JdbcTunerStore;
import com.xp.starsite.tuner.store.memory.MemoryTunerStore;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 自动装配（memory/jdbc 路径；MyBatis 路径见 {@link TunerMyBatisAutoConfiguration}，
 * imports 中先于本类）。装配序（@ConditionalOnMissingBean 全量让位）：
 * SQL 方言 → 存储 → 引擎（TunerClient 即引擎）→ 轮询生命周期 → admin → 健康端点。
 *
 * @author muyi
 * @date 2026/11/14
 */
@AutoConfiguration
@EnableConfigurationProperties(TunerProperties.class)
@ConditionalOnProperty(prefix = "starsite.tuner", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class TunerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TunerAutoConfiguration.class);

    /**
     * 装配 SQL 方言（自定义方言让位）。
     *
     * @return MySQL 方言
     */
    @Bean
    @ConditionalOnMissingBean
    public TunerSql tunerSql() {
        return new TunerSql.MySql();
    }

    /**
     * 装配治理存储：store=jdbc 有 DataSource 走 JDBC；store=auto 探测；否则 memory。
     * （治理数据是共享参数源——分布式必须 jdbc/mybatis；memory 仅单机。）
     *
     * @param props       配置
     * @param jdbcTemplate JdbcTemplate（可缺）
     * @param idGenerator id 发号器（sequence 组件）
     * @param sql         SQL 方言
     * @return 治理存储
     */
    @Bean
    @ConditionalOnMissingBean(TunerStore.class)
    public TunerStore tunerStore(TunerProperties props,
                                 ObjectProvider<JdbcTemplate> jdbcTemplate,
                                 ObjectProvider<StarsiteIdGenerator> idGenerator,
                                 TunerSql sql) {
        JdbcTemplate jdbc = jdbcTemplate.getIfAvailable();
        boolean wantJdbc = props.getStore() == TunerProperties.Store.JDBC
                || (props.getStore() == TunerProperties.Store.AUTO && jdbc != null);
        if (wantJdbc && jdbc != null) {
            log.info("[starsite-tuner] 存储形态：jdbc（表前缀 {}）", props.getTablePrefix());
            return new JdbcTunerStore(jdbc, props.getTablePrefix(), sql, true,
                    resolveIds(idGenerator.getIfAvailable()), TimeSource.system());
        }
        if (wantJdbc) {
            log.warn("[starsite-tuner] store=jdbc 但无 DataSource——降级 memory（单机治理）");
        } else {
            log.info("[starsite-tuner] 存储形态：memory（单机治理；分布式治理必须 jdbc/mybatis）");
        }
        return new MemoryTunerStore(TimeSource.system());
    }

    /**
     * 装配引擎（{@link com.xp.starsite.tuner.api.TunerClient} 门面本体）。
     *
     * @param store 治理存储
     * @param props 配置
     * @return 引擎
     */
    @Bean
    @ConditionalOnMissingBean
    public TunerEngine tunerEngine(TunerStore store, TunerProperties props) {
        return new TunerEngine(store, TimeSource.system(), ZoneId.of(props.getZone()));
    }

    /**
     * 装配轮询生命周期（SmartLifecycle：phase MAX 最后启动最先停止；空轮询成本≈一次
     * versionStamp 查询）。
     *
     * @param engine 引擎
     * @param props  配置
     * @return 生命周期
     */
    @Bean
    @ConditionalOnMissingBean
    public TunerLifecycle tunerLifecycle(TunerEngine engine, TunerProperties props) {
        return new TunerLifecycle(engine, props.getPollIntervalSeconds());
    }

    /**
     * 装配 admin 守卫（自定义 Guard Bean 让位；token 配置启用内置守卫；都无则放行+WARN
     * ——async 三级鉴权同款）。
     *
     * @param props 配置
     * @return 守卫（可 null 语义经 admin 控制器判空处理——这里恒返回实例，内部标记放行）
     */
    @Bean
    @ConditionalOnMissingBean(com.xp.starsite.tuner.admin.TunerAdminGuard.class)
    @ConditionalOnProperty(prefix = "starsite.tuner", name = "admin-enabled",
            havingValue = "true", matchIfMissing = false)
    public com.xp.starsite.tuner.admin.TunerAdminGuard tunerAdminGuard(TunerProperties props) {
        if (props.getAdminToken() != null && !props.getAdminToken().isBlank()) {
            return new com.xp.starsite.tuner.admin.StaticTokenAdminGuard(props.getAdminToken());
        }
        log.warn("[starsite-tuner] admin 已启用但未配置守卫（自定义 TunerAdminGuard Bean 或 "
                + "admin-token）——治理端点将放行，仅限本地开发");
        return request -> true;
    }

    /**
     * 装配治理端点（admin-enabled=true 且 Web 应用才装配；类级 @ResponseBody 防
     * 宿主宽扫描绕过条件装配——BUG-008 家族铁律）。
     *
     * @param client      治理门面
     * @param props       配置
     * @param guardProvider 守卫提供器（可缺——装配期 WARN 放行形态）
     * @return 治理端点
     */
    @Bean
    @ConditionalOnProperty(prefix = "starsite.tuner", name = "admin-enabled",
            havingValue = "true", matchIfMissing = false)
    @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
    public com.xp.starsite.tuner.admin.TunerAdminController tunerAdminController(
            TunerEngine client, TunerProperties props,
            ObjectProvider<com.xp.starsite.tuner.admin.TunerAdminGuard> guardProvider) {
        return new com.xp.starsite.tuner.admin.TunerAdminController(client,
                guardProvider.getIfAvailable());
    }

    /**
     * 装配健康端点（引 actuator 即有 starsiteTuner）。
     *
     * @param engine 引擎
     * @param store  治理存储
     * @return 健康端点
     */
    @Bean
    @ConditionalOnMissingBean(name = "tunerHealthIndicator")
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    public TunerHealthIndicator tunerHealthIndicator(TunerEngine engine, TunerStore store) {
        return new TunerHealthIndicator(engine, store);
    }

    /** id 发号：sequence 在场用之；缺席降级毫秒基线发号并 warn（家族同形） */
    private static LongSupplier resolveIds(StarsiteIdGenerator generator) {
        if (generator != null) {
            return generator::next;
        }
        log.warn("[starsite-tuner] 容器无 StarsiteIdGenerator——治理 id 降级毫秒基线发号");
        AtomicLong fallback = new AtomicLong(System.currentTimeMillis() * 1000L);
        return fallback::getAndIncrement;
    }
}
