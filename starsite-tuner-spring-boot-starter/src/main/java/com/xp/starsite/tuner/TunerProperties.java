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

    /** 活跃度告警阈值（活跃/max，0.0~1.0；1.1=禁用） */
    private double alarmActiveRatio = 0.9;

    /** 队列水位告警阈值（积压/容量，0.0~1.0；仅可变容量队列；1.1=禁用） */
    private double alarmQueueRatio = 0.8;

    /** 告警冷却分钟（同池同类型防风暴） */
    private long alarmCooldownMinutes = 5;

    /** 自适应冷却分钟（防规则抖动） */
    private long adaptiveCooldownMinutes = 2;

    /** 审计保留天数（<=0 不清理——合规默认长保留） */
    private int auditRetentionDays = 0;

    /** 审计清理批量 */
    private int auditPurgeBatch = 500;

    /**
     * 获取活跃度告警阈值。
     *
     * @return 阈值
     */
    public double getAlarmActiveRatio() {
        return alarmActiveRatio;
    }

    /**
     * 设置活跃度告警阈值。
     *
     * @param alarmActiveRatio 阈值（0.0~1.0）
     */
    public void setAlarmActiveRatio(double alarmActiveRatio) {
        this.alarmActiveRatio = alarmActiveRatio;
    }

    /**
     * 获取队列水位告警阈值。
     *
     * @return 阈值
     */
    public double getAlarmQueueRatio() {
        return alarmQueueRatio;
    }

    /**
     * 设置队列水位告警阈值。
     *
     * @param alarmQueueRatio 阈值（0.0~1.0）
     */
    public void setAlarmQueueRatio(double alarmQueueRatio) {
        this.alarmQueueRatio = alarmQueueRatio;
    }

    /**
     * 获取告警冷却分钟。
     *
     * @return 冷却分钟
     */
    public long getAlarmCooldownMinutes() {
        return alarmCooldownMinutes;
    }

    /**
     * 设置告警冷却分钟。
     *
     * @param alarmCooldownMinutes 冷却分钟（>=1）
     */
    public void setAlarmCooldownMinutes(long alarmCooldownMinutes) {
        this.alarmCooldownMinutes = alarmCooldownMinutes;
    }

    /**
     * 获取自适应冷却分钟。
     *
     * @return 冷却分钟
     */
    public long getAdaptiveCooldownMinutes() {
        return adaptiveCooldownMinutes;
    }

    /**
     * 设置自适应冷却分钟。
     *
     * @param adaptiveCooldownMinutes 冷却分钟（>=1）
     */
    public void setAdaptiveCooldownMinutes(long adaptiveCooldownMinutes) {
        this.adaptiveCooldownMinutes = adaptiveCooldownMinutes;
    }

    /**
     * 获取审计保留天数。
     *
     * @return 保留天数（<=0 不清理）
     */
    public int getAuditRetentionDays() {
        return auditRetentionDays;
    }

    /**
     * 设置审计保留天数。
     *
     * @param auditRetentionDays 保留天数
     */
    public void setAuditRetentionDays(int auditRetentionDays) {
        this.auditRetentionDays = auditRetentionDays;
    }

    /**
     * 获取审计清理批量。
     *
     * @return 批量
     */
    public int getAuditPurgeBatch() {
        return auditPurgeBatch;
    }

    /**
     * 设置审计清理批量。
     *
     * @param auditPurgeBatch 批量
     */
    public void setAuditPurgeBatch(int auditPurgeBatch) {
        this.auditPurgeBatch = auditPurgeBatch;
    }

    /**
     * 告警冷却换算 Duration。
     *
     * @return 冷却时长
     */
    public java.time.Duration getAlarmCooldown() {
        return java.time.Duration.ofMinutes(Math.max(1, alarmCooldownMinutes));
    }

    /**
     * 自适应冷却换算 Duration。
     *
     * @return 冷却时长
     */
    public java.time.Duration getAdaptiveCooldown() {
        return java.time.Duration.ofMinutes(Math.max(1, adaptiveCooldownMinutes));
    }

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
