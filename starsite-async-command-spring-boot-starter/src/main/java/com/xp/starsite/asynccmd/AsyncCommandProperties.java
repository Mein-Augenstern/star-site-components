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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 异步命令配置（前缀 starsite.async-command）——继承 core 的 {@link AsyncCommandConfig} 完成绑定，
 * 本类只追加装配形态相关项。
 * <p>
 * 存储双形态：
 * <ul>
 *   <li><b>jdbc</b>（默认）：表存储 + 令牌认领——单机单实例与分布式多实例同语义，
 *       多实例并发认领靠 {@code UPDATE ... claim_token} 互斥，谁写入算谁。</li>
 *   <li><b>memory</b>：进程内存队列，无 DB 场景（本地开发/小型工具/单机服务）。
 *       重启丢命令，不适用于有可靠性要求的链路。</li>
 * </ul>
 *
 * @author muyi
 * @since 2026-10
 */
@ConfigurationProperties(prefix = "starsite.async-command")
public class AsyncCommandProperties extends AsyncCommandConfig {

    /** 组件总开关（false 时完全不装配） */
    private boolean enabled = true;

    /** 存储形态：jdbc / memory */
    private Store store = Store.JDBC;

    /** 命令表名（jdbc 形态；每服务独立库/独立表名即可多服务共存） */
    private String tableName = "async_command";

    /** 自动建表（IF NOT EXISTS，增量安全；false = 交由迁移脚本管理） */
    private boolean initDdl = true;

    /** 管控端点开关（默认关；开启需 Web 应用，建议仅内网/网关白名单可达） */
    private boolean adminEnabled = false;

    /** 管控端点基路径 */
    private String adminBasePath = "/api/admin/async";

    public enum Store { JDBC, MEMORY }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Store getStore() { return store; }
    public void setStore(Store store) { this.store = store; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public boolean isInitDdl() { return initDdl; }
    public void setInitDdl(boolean initDdl) { this.initDdl = initDdl; }
    public boolean isAdminEnabled() { return adminEnabled; }
    public void setAdminEnabled(boolean adminEnabled) { this.adminEnabled = adminEnabled; }
    public String getAdminBasePath() { return adminBasePath; }
    public void setAdminBasePath(String adminBasePath) { this.adminBasePath = adminBasePath; }
}
