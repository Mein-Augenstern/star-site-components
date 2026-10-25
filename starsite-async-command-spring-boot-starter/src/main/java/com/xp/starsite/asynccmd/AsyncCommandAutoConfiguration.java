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
 * @date 2026/10/25
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "starsite.async-command", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AsyncCommandProperties.class)
public class AsyncCommandAutoConfiguration {

    /** 日志实例（slf4j，用于打印存储形态等装配期关键信息） */
    private static final Logger log = LoggerFactory.getLogger(AsyncCommandAutoConfiguration.class);

    /**
     * 装配命令存储 Bean：按 {@code starsite.async-command.store} 选择形态——
     * jdbc（默认，表存储 + 令牌认领）或 memory（无 DB 单机内存队列）。
     * <p>让位语义：@ConditionalOnMissingBean——应用已自定义 {@link AsyncCommandStore} 时本方法不生效。
     *
     * @param props 异步命令配置（读取 store/tableName/initDdl 三项）
     * @param jdbcTemplateProvider JdbcTemplate 惰性提供者（仅 jdbc 形态使用，容器中可能不存在）
     * @return 命令存储实例（jdbc 形态返回 {@link JdbcAsyncCommandStore}，memory 形态返回 {@link InMemoryAsyncCommandStore}）
     * @throws IllegalStateException store=jdbc 但容器中无 JdbcTemplate（缺 DataSource）时快速失败，
     *                               异常信息提示改用 store=memory
     */
    @Bean
    @ConditionalOnMissingBean
    public AsyncCommandStore asyncCommandStore(AsyncCommandProperties props,
                                               ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        // 枚举 switch 表达式：MEMORY/JDBC 两分支穷尽且均以 yield 终止，无需 default
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

    /**
     * 装配命令提交客户端 Bean：业务侧通过它提交命令（submit）并查询结果。
     * <p>让位语义：@ConditionalOnMissingBean——应用已自定义 {@link AsyncCommandClient} 时本方法不生效。
     *
     * @param store 命令存储（依赖上方存储 Bean）
     * @param props 异步命令配置（未显式配置 owner 时在本方法内回填默认值）
     * @param env Spring 环境（用于读取 spring.application.name 作为 owner 兜底）
     * @return 命令客户端实例（owner 未配置时已回填为应用名或 unknown）
     */
    @Bean
    @ConditionalOnMissingBean
    public AsyncCommandClient asyncCommandClient(AsyncCommandStore store, AsyncCommandProperties props,
                                                 Environment env) {
        // 未显式配置 owner 时回填应用名，保证多实例下的认领主体可区分、可追溯
        if (props.getOwner() == null || props.getOwner().isBlank()) {
            props.setOwner(env.getProperty("spring.application.name", "unknown"));
        }
        return new AsyncCommandClient(store, props);
    }

    /**
     * 装配执行器注册表 Bean：收集容器中全部 {@link AsyncCommandExecutor}，供调度器按命令 type 路由。
     * <p>让位语义：@ConditionalOnMissingBean——应用已自定义 {@link ExecutorRegistry} 时本方法不生效。
     *
     * @param executors 容器中执行器列表的惰性提供者（可能为空——无执行器时对应类型的命令将无法执行）
     * @return 执行器注册表实例（内部以 supplier 惰性拉取执行器列表）
     */
    @Bean
    @ConditionalOnMissingBean
    public ExecutorRegistry asyncCommandExecutorRegistry(ObjectProvider<List<AsyncCommandExecutor>> executors) {
        return new ExecutorRegistry(() -> executors.getIfAvailable());
    }

    /**
     * 装配调度器 Bean：到期命令认领与执行引擎（本体在 core，零 Spring 依赖）。
     * <p>让位语义：@ConditionalOnMissingBean——应用已自定义 {@link AsyncCommandScheduler} 时本方法不生效。
     * 注意：本方法只创建不启动，start/stop 由 {@link AsyncCommandLifecycle} 以 SmartLifecycle 托管。
     *
     * @param store 命令存储（认领/回写状态）
     * @param client 命令客户端（复用其提交与状态语义）
     * @param props 异步命令配置（认领间隔/批量/重试等核心参数继承自 core 的 AsyncCommandConfig）
     * @param registry 执行器注册表（按 type 路由执行）
     * @return 调度器实例（创建后处于未启动状态，待生命周期回调拉起）
     */
    @Bean
    @ConditionalOnMissingBean
    public AsyncCommandScheduler asyncCommandScheduler(AsyncCommandStore store, AsyncCommandClient client,
                                                       AsyncCommandProperties props,
                                                       ExecutorRegistry registry) {
        // start/stop 由下方 SmartLifecycle 包装托管（容器最后启动、最先停止）
        return new AsyncCommandScheduler(store, client, props, registry);
    }

    /**
     * 装配调度器的 Spring 生命周期适配 Bean：把 core 调度器的 start/stop 纳入容器生命周期托管，
     * 容器 refresh 完成后自动开始调度、容器关闭前优先停止调度。
     * <p>让位语义：@ConditionalOnMissingBean——应用已自定义 {@link AsyncCommandLifecycle} 时本方法不生效。
     *
     * @param scheduler 被托管的调度器（本 Bean 的 start/stop/isRunning 全部委托给它）
     * @return 生命周期适配实例（phase = Integer.MAX_VALUE：容器中最后启动、最先停止）
     */
    @Bean
    @ConditionalOnMissingBean
    public AsyncCommandLifecycle asyncCommandLifecycle(AsyncCommandScheduler scheduler) {
        return new AsyncCommandLifecycle(scheduler);
    }

    /**
     * 装配健康端点 Bean：actuator 在 classpath 时向 /actuator/health 附 starsiteAsyncCommand 明细——
     * 各状态计数与到期积压，存在死信即 DOWN。
     * <p>装配条件：classpath 存在 {@code org.springframework.boot.actuate.health.HealthIndicator}；
     * 让位语义：@ConditionalOnMissingBean(name = "asyncCommandHealthIndicator")——应用已自定义同名 Bean 时让位。
     *
     * @param store 命令存储（健康检查读取各状态计数）
     * @return 健康端点实例
     */
    @Bean
    @ConditionalOnMissingBean(name = "asyncCommandHealthIndicator")
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    public AsyncCommandHealthIndicator asyncCommandHealthIndicator(AsyncCommandStore store) {
        return new AsyncCommandHealthIndicator(store);
    }

    /**
     * 装配管控服务 Bean：积压/死信可视化与人工处置（重试/重占/终止）的本体，纯 core 无 Web 依赖。
     * <p>装配条件：{@code starsite.async-command.admin-enabled=true}（默认关闭）；
     * 让位语义：@ConditionalOnMissingBean——应用已自定义 {@link AsyncCommandAdminService} 时本方法不生效。
     *
     * @param store 命令存储（查询与处置均直接操作存储）
     * @return 管控服务实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "starsite.async-command", name = "admin-enabled", havingValue = "true")
    public AsyncCommandAdminService asyncCommandAdminService(AsyncCommandStore store) {
        return new AsyncCommandAdminService(store);
    }

    /**
     * 装配管控 HTTP 端点 Bean：暴露 stats/recent/list 查询与 retry/requeue/kill 处置端点。
     * <p>装配条件：Web 应用 + {@code starsite.async-command.admin-enabled=true}（默认关闭）；
     * 让位语义：@ConditionalOnMissingBean——应用已自定义 {@link AsyncCommandAdminController} 时本方法不生效。
     * 安全注意：无内置鉴权，须确保仅内网/网关白名单可达。
     *
     * @param admin 管控服务（端点直接委托给它）
     * @return 管控端点 Controller 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication
    @ConditionalOnProperty(prefix = "starsite.async-command", name = "admin-enabled", havingValue = "true")
    public AsyncCommandAdminController asyncCommandAdminController(AsyncCommandAdminService admin) {
        return new AsyncCommandAdminController(admin);
    }
}
