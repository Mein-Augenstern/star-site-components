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
package com.xp.starsite.schedule;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 配置项（starsite.schedule.*）。
 *
 * <pre>
 * starsite.schedule.enabled=true          # 组件总开关（false=完全让位）
 * starsite.schedule.store=auto            # auto/memory/jdbc/mybatis
 * starsite.schedule.table-name=schedule_run_log
 * starsite.schedule.pool-size=2           # 任务执行池
 * starsite.schedule.tick-millis=500       # 触发扫描间隔
 * starsite.schedule.stale-threshold-seconds=3600  # 悬挂 RUNNING 判死阈值
 * </pre>
 *
 * @author muyi
 * @date 2026/11/02
 */
@ConfigurationProperties(prefix = "starsite.schedule")
public class ScheduleProperties {

    /** 存储形态 */
    public enum Store {
        /** 自动探测：MyBatis 显式配置优先，有 DataSource 走 jdbc，否则 memory */
        AUTO,
        /** 内存（单机观测/测试） */
        MEMORY,
        /** JDBC（MySQL 方言默认） */
        JDBC,
        /** MyBatis（消费方 classpath 自带 mybatis） */
        MYBATIS
    }

    /** 组件总开关（false 时自动装配整体让位） */
    private boolean enabled = true;

    /** 存储形态 */
    private Store store = Store.AUTO;

    /** 流水表名（白名单 [A-Za-z0-9_]） */
    private String tableName = "schedule_run_log";

    /** 任务执行池大小 */
    private int poolSize = 2;

    /** 触发扫描间隔毫秒 */
    private long tickMillis = 500;

    /** 悬挂 RUNNING 判死阈值（秒）——应大于任务最长执行时长 */
    private long staleThresholdSeconds = 3600;

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
     * 获取表名。
     *
     * @return 表名
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * 设置表名。
     *
     * @param tableName 表名
     */
    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    /**
     * 获取执行池大小。
     *
     * @return 池大小
     */
    public int getPoolSize() {
        return poolSize;
    }

    /**
     * 设置执行池大小。
     *
     * @param poolSize 池大小
     */
    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    /**
     * 获取扫描间隔。
     *
     * @return 间隔毫秒
     */
    public long getTickMillis() {
        return tickMillis;
    }

    /**
     * 设置扫描间隔。
     *
     * @param tickMillis 间隔毫秒
     */
    public void setTickMillis(long tickMillis) {
        this.tickMillis = tickMillis;
    }

    /**
     * 获取悬挂判死阈值。
     *
     * @return 阈值秒
     */
    public long getStaleThresholdSeconds() {
        return staleThresholdSeconds;
    }

    /**
     * 设置悬挂判死阈值。
     *
     * @param staleThresholdSeconds 阈值秒
     */
    public void setStaleThresholdSeconds(long staleThresholdSeconds) {
        this.staleThresholdSeconds = staleThresholdSeconds;
    }

    /**
     * 悬挂阈值换算 Duration（装配用）。
     *
     * @return 阈值时长
     */
    public Duration staleThreshold() {
        return Duration.ofSeconds(Math.max(1, staleThresholdSeconds));
    }
}
