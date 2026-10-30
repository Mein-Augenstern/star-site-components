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

import com.xp.starsite.asynccmd.config.AsyncCommandConfig;
import com.xp.starsite.asynccmd.executor.ScheduleMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 异步命令配置（前缀 starsite.async-command）——继承 core 的 {@link AsyncCommandConfig} 完成绑定，
 * 本类只追加装配形态相关项。
 * <p>
 * 双形态（键名 mode，与 sequence/lock 组件统一）：
 * <ul>
 *   <li><b>mysql</b>（默认）：表存储 + 令牌认领——单机单实例与分布式多实例同语义，
 *       多实例并发认领靠 {@code UPDATE ... claim_token} 互斥，谁写入算谁。</li>
 *   <li><b>standalone</b>：进程内存队列，无 DB 场景（本地开发/小型工具/单机服务）。
 *       重启丢命令，不适用于有可靠性要求的链路。</li>
 * </ul>
 *
 * @author muyi
 * @date 2026/10/25
 */
@ConfigurationProperties(prefix = "starsite.async-command")
public class AsyncCommandProperties extends AsyncCommandConfig {

    /** 组件总开关（默认 true；false 时 starter 完全不装配任何 Bean） */
    private boolean enabled = true;

    /** 存储形态（默认 MYSQL；取值语义见 {@link Mode} 各常量说明；键名与 sequence/lock 统一为 mode） */
    private Mode mode = Mode.MYSQL;

    /** 命令表名（仅 mysql 形态生效；默认 async_command；每服务独立库/独立表名即可多服务共存） */
    private String tableName = "async_command";

    /** 自动建表开关（仅 mysql 形态生效；默认 true，CREATE TABLE IF NOT EXISTS 增量安全；false = 交由迁移脚本管理） */
    private boolean initDdl = true;

    /** 管控端点开关（默认 false；true 且 Web 应用时才装配管控端点，建议仅内网/网关白名单可达） */
    private boolean adminEnabled = false;

    /** 管控端点基路径（默认 /api/admin/async，Controller @RequestMapping 直接引用） */
    private String adminBasePath = "/api/admin/async";

    /**
     * 管控端点静态 Token（默认空 = 不启用内置守卫）：
     * 配置非空值后启用内置 StaticTokenAdminGuard，校验请求头 X-Starsite-Admin-Token（常量时间比对）。
     * 应用自定义 AsyncCommandAdminGuard Bean 时本配置被忽略（自定义优先）。
     */
    private String adminToken = "";

    /** 调度触发模式（COMPETING 默认 / LEADER / EXTERNAL，取值语义见 ScheduleMode 枚举注释）。 */
    private ScheduleMode scheduleMode = ScheduleMode.COMPETING;

    /** LEADER 模式锁 TTL 毫秒（leader 崩溃后其他实例在此时间后接管；应大于 poll-interval-ms）。 */
    private long scheduleLockTtlMs = 30000;

    /**
     * 存储形态枚举：决定命令落库方式与可用部署拓扑（取值与 sequence/lock 组件统一词汇表）。
     */
    public enum Mode {

        /**
         * 表存储 + 令牌认领：需容器中存在 DataSource/JdbcTemplate；
         * 单机单实例与分布式多实例同语义，多实例并发认领靠 UPDATE claim_token 互斥，谁写入算谁。
         */
        MYSQL,

        /**
         * 进程内存队列：无需任何 DB，适用于本地开发/小型工具/单机服务；
         * 重启即丢失全部未完成命令，不适用于有可靠性要求的链路。
         */
        STANDALONE
    }

    /**
     * 读取组件总开关。
     *
     * @return true 表示启用装配；false 表示完全不装配
     */
    public boolean isEnabled() { return enabled; }

    /**
     * 设置组件总开关。
     *
     * @param enabled true 启用（默认）；false 完全不装配
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * 读取存储形态。
     *
     * @return 当前存储形态（默认 {@link Mode#MYSQL}）
     */
    public Mode getMode() { return mode; }

    /**
     * 设置存储形态。
     *
     * @param mode 存储形态：MYSQL（需 DataSource）或 STANDALONE（无 DB、重启丢命令）
     */
    public void setMode(Mode mode) { this.mode = mode; }

    /**
     * 读取命令表名。
     *
     * @return 命令表名（仅 mysql 形态生效；默认 async_command）
     */
    public String getTableName() { return tableName; }

    /**
     * 设置命令表名。
     *
     * @param tableName 命令表名（仅 mysql 形态生效；多服务共存时每服务配独立库/独立表名）
     */
    public void setTableName(String tableName) { this.tableName = tableName; }

    /**
     * 读取自动建表开关。
     *
     * @return true 表示启动时 CREATE TABLE IF NOT EXISTS（默认）；false 表示交由迁移脚本管理
     */
    public boolean isInitDdl() { return initDdl; }

    /**
     * 设置自动建表开关。
     *
     * @param initDdl true 自动建表（增量安全）；false 不自动建表（表结构由迁移脚本负责）
     */
    public void setInitDdl(boolean initDdl) { this.initDdl = initDdl; }

    /**
     * 读取管控端点开关。
     *
     * @return true 表示装配管控服务与端点；false 表示不装配（默认）
     */
    public boolean isAdminEnabled() { return adminEnabled; }

    /**
     * 设置管控端点开关。
     *
     * @param adminEnabled true 开启（还需 Web 应用才暴露 HTTP 端点）；false 关闭（默认）
     */
    public void setAdminEnabled(boolean adminEnabled) { this.adminEnabled = adminEnabled; }

    /**
     * 读取管控端点基路径。
     *
     * @return 管控端点基路径（默认 /api/admin/async）
     */
    public String getAdminBasePath() { return adminBasePath; }

    /**
     * 设置管控端点基路径。
     *
     * @param adminBasePath 管控端点基路径（所有管控端点挂在该路径之下）
     */
    public void setAdminBasePath(String adminBasePath) { this.adminBasePath = adminBasePath; }

    /**
     * 读取管控端点静态 Token。
     *
     * @return 配置的 Token；空串表示未启用内置守卫（放行或依赖自定义守卫）
     */
    public String getAdminToken() { return adminToken; }

    /**
     * 设置管控端点静态 Token（配置非空即启用内置守卫；自定义 Guard Bean 存在时忽略）。
     *
     * @param adminToken 静态 Token（建议高熵随机串，经环境变量注入而非写死在仓库）
     */
    public void setAdminToken(String adminToken) { this.adminToken = adminToken; }

    /** 读取调度触发模式。@return 当前模式（默认 COMPETING） */
    public ScheduleMode getScheduleMode() { return scheduleMode; }
    /** 设置调度触发模式。@param scheduleMode COMPETING / LEADER / EXTERNAL */
    public void setScheduleMode(ScheduleMode scheduleMode) { this.scheduleMode = scheduleMode; }
    /** 读取 LEADER 锁 TTL。@return TTL 毫秒（默认 30000） */
    public long getScheduleLockTtlMs() { return scheduleLockTtlMs; }
    /** 设置 LEADER 锁 TTL。@param scheduleLockTtlMs 毫秒（大于 poll-interval-ms） */
    public void setScheduleLockTtlMs(long scheduleLockTtlMs) { this.scheduleLockTtlMs = scheduleLockTtlMs; }
}
