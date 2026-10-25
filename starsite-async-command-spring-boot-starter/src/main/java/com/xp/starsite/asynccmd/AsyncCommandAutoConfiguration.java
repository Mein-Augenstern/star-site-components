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

import com.xp.starsite.asynccmd.admin.AsyncCommandAdminController;
import com.xp.starsite.asynccmd.admin.AsyncCommandAdminService;
import com.xp.starsite.asynccmd.health.AsyncCommandHealthIndicator;
import com.xp.starsite.asynccmd.store.AsyncCommandStore;
import com.xp.starsite.asynccmd.store.InMemoryAsyncCommandStore;
import com.xp.starsite.asynccmd.store.JdbcAsyncCommandStore;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * starsite-async-command 自动装配（starter 模块，仅粘合；命令/存储/调度本体在 core）：
 * 引 jar + 配置即用，无需 @Import / @EnableScheduling。
 * <pre>
 * DB 服务（单机或分布式）：starsite.async-command.store=jdbc（默认，需 DataSource）
 * 无 DB 单机：starsite.async-command.store=memory
 * 管控端点：starsite.async-command.admin-enabled=true（默认关，Web 应用才生效）
 * </pre>
 * 应用自定义 Store/Client/Scheduler Bean 时让位（@ConditionalOnMissingBean）；
 * actuator 在 classpath 时附赠健康端点（状态计数 + 死信即 DOWN）。
 *
 * @author muyi
 * @since 2026-10
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "starsite.async-command", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AsyncCommandProperties.class)
public class AsyncCommandAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AsyncCommandAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public AsyncCommandStore asyncCommandStore(AsyncCommandProperties props,
                                               ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        return switch (props.getStore()) {
            case MEMORY -> {
                log.info("[starsite-async-command] 存储形态：memory（单机无 DB；重启丢命令）");
                yield new InMemoryAsyncCommandStore();
            }
            case JDBC -> {
                JdbcTemplate jdbc = jdbcTemplateProvider.getIfAvailable();
                if (jdbc == null) {
                    throw new IllegalStateException(
                            "starsite.async-command.store=jdbc 需要 JdbcTemplate（DataSource）；"
                                    + "无 DB 单机场景请配置 starsite.async-command.store=memory");
                }
                log.info("[starsite-async-command] 存储形态：jdbc（table={}，单机/分布式通用令牌认领）",
                        props.getTableName());
                yield new JdbcAsyncCommandStore(jdbc, props.getTableName(), props.isInitDdl());
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public AsyncCommandClient asyncCommandClient(AsyncCommandStore store, AsyncCommandProperties props,
                                                 Environment env) {
        if (props.getOwner() == null || props.getOwner().isBlank()) {
            props.setOwner(env.getProperty("spring.application.name", "unknown"));
        }
        return new AsyncCommandClient(store, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExecutorRegistry asyncCommandExecutorRegistry(ObjectProvider<List<AsyncCommandExecutor>> executors) {
        return new ExecutorRegistry(() -> executors.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public AsyncCommandScheduler asyncCommandScheduler(AsyncCommandStore store, AsyncCommandClient client,
                                                       AsyncCommandProperties props,
                                                       ExecutorRegistry registry) {
        // start/stop 由下方 SmartLifecycle 包装托管（容器最后启动、最先停止）
        return new AsyncCommandScheduler(store, client, props, registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public AsyncCommandLifecycle asyncCommandLifecycle(AsyncCommandScheduler scheduler) {
        return new AsyncCommandLifecycle(scheduler);
    }

    @Bean
    @ConditionalOnMissingBean(name = "asyncCommandHealthIndicator")
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    public AsyncCommandHealthIndicator asyncCommandHealthIndicator(AsyncCommandStore store) {
        return new AsyncCommandHealthIndicator(store);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "starsite.async-command", name = "admin-enabled", havingValue = "true")
    public AsyncCommandAdminService asyncCommandAdminService(AsyncCommandStore store) {
        return new AsyncCommandAdminService(store);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication
    @ConditionalOnProperty(prefix = "starsite.async-command", name = "admin-enabled", havingValue = "true")
    public AsyncCommandAdminController asyncCommandAdminController(AsyncCommandAdminService admin) {
        return new AsyncCommandAdminController(admin);
    }
}
