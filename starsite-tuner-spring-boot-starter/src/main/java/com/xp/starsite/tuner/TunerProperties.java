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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 配置项（starsite.tuner.*）。
 *
 * <pre>
 * starsite.tuner.enabled=true                # 组件总开关（false=完全让位）
 * starsite.tuner.store=auto                  # auto/memory/jdbc/mybatis
 * starsite.tuner.table-prefix=tuner          # 治理三表前缀（_preset/_window/_binding/_audit）
 * starsite.tuner.poll-interval-seconds=30    # 轮询间隔（窗口生效精度上限）
 * starsite.tuner.zone=Asia/Shanghai          # 窗口 cron 求值时区
 * starsite.tuner.admin-enabled=false         # admin 端点开关（Web 应用才装配）
 * starsite.tuner.admin-base-path=/api/admin/tuner
 * starsite.tuner.admin-token=                # 内置守卫 token（空=自定义 Guard 或放行+WARN）
 * </pre>
 *
 * @author muyi
 * @date 2026/11/14
 */
@ConfigurationProperties(prefix = "starsite.tuner")
public class TunerProperties {

    /** 存储形态 */
    public enum Store {
        /** 自动探测：有 DataSource 走 jdbc，否则 memory */
        AUTO,
        /** 内存（单机：重启丢治理配置） */
        MEMORY,
        /** JDBC（MySQL 方言默认） */
        JDBC,
        /** MyBatis（消费方 classpath 自带 mybatis） */
        MYBATIS
    }

    /** 组件总开关 */
    private boolean enabled = true;

    /** 存储形态 */
    private Store store = Store.AUTO;

    /** 治理三表前缀 */
    private String tablePrefix = "tuner";

    /** 轮询间隔秒（窗口生效精度上限） */
    private int pollIntervalSeconds = 30;

    /** 窗口 cron 求值时区 */
    private String zone = "Asia/Shanghai";

    /** admin 端点开关 */
    private boolean adminEnabled = false;

    /** admin 基础路径 */
    private String adminBasePath = "/api/admin/tuner";

    /** 内置守卫 token（空=自定义 Guard 或放行+WARN——async 三级鉴权同款） */
    private String adminToken = "";

    /**
     * 获取总开关。
     *
     * @return true 启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置总开关。
     *
     * @param enabled 总开关
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 获取存储形态。
     *
     * @return 存储形态
     */
    public Store getStore() {
        return store;
    }

    /**
     * 设置存储形态。
     *
     * @param store 存储形态
     */
    public void setStore(Store store) {
        this.store = store;
    }

    /**
     * 获取治理表前缀。
     *
     * @return 表前缀
     */
    public String getTablePrefix() {
        return tablePrefix;
    }

    /**
     * 设置治理表前缀。
     *
     * @param tablePrefix 表前缀（白名单 [A-Za-z0-9_]）
     */
    public void setTablePrefix(String tablePrefix) {
        this.tablePrefix = tablePrefix;
    }

    /**
     * 获取轮询间隔秒。
     *
     * @return 间隔秒
     */
    public int getPollIntervalSeconds() {
        return pollIntervalSeconds;
    }

    /**
     * 设置轮询间隔秒。
     *
     * @param pollIntervalSeconds 间隔秒（>=1）
     */
    public void setPollIntervalSeconds(int pollIntervalSeconds) {
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    /**
     * 获取求值时区。
     *
     * @return 时区 ID
     */
    public String getZone() {
        return zone;
    }

    /**
     * 设置求值时区。
     *
     * @param zone 时区 ID
     */
    public void setZone(String zone) {
        this.zone = zone;
    }

    /**
     * 获取 admin 开关。
     *
     * @return true 启用
     */
    public boolean isAdminEnabled() {
        return adminEnabled;
    }

    /**
     * 设置 admin 开关。
     *
     * @param adminEnabled admin 开关
     */
    public void setAdminEnabled(boolean adminEnabled) {
        this.adminEnabled = adminEnabled;
    }

    /**
     * 获取 admin 基础路径。
     *
     * @return 基础路径
     */
    public String getAdminBasePath() {
        return adminBasePath;
    }

    /**
     * 设置 admin 基础路径。
     *
     * @param adminBasePath 基础路径
     */
    public void setAdminBasePath(String adminBasePath) {
        this.adminBasePath = adminBasePath;
    }

    /**
     * 获取内置守卫 token。
     *
     * @return token（空串=未配置）
     */
    public String getAdminToken() {
        return adminToken;
    }

    /**
     * 设置内置守卫 token。
     *
     * @param adminToken token
     */
    public void setAdminToken(String adminToken) {
        this.adminToken = adminToken;
    }
}
