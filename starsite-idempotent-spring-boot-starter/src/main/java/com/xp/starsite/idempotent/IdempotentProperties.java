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
package com.xp.starsite.idempotent;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 配置项（starsite.idempotent.*）。
 *
 * <pre>
 * starsite.idempotent.enabled=true                # 组件总开关（false=完全让位）
 * starsite.idempotent.store=auto                  # auto/memory/jdbc/mybatis
 * starsite.idempotent.table-name=idempotent_record
 * starsite.idempotent.key-ttl-hours=24            # 键记忆窗口（Stripe 同款 24h）
 * starsite.idempotent.wait-millis=3000            # 在飞重复请求等待窗口
 * starsite.idempotent.poll-millis=100             # 等待轮询间隔
 * starsite.idempotent.processing-stale-seconds=120  # PROCESSING 停滞判崩溃遗留阈值
 * starsite.idempotent.retention-enabled=true      # 过期行清理看护
 * starsite.idempotent.retention-batch-size=500    # 单轮清理批量
 * starsite.idempotent.purge-interval-minutes=60   # 清理看护间隔
 * </pre>
 *
 * @author muyi
 * @date 2026/11/12
 */
@ConfigurationProperties(prefix = "starsite.idempotent")
public class IdempotentProperties {

    /** 存储形态 */
    public enum Store {
        /** 自动探测：显式 mybatis 优先（见 MyBatis 装配），有 DataSource 走 jdbc，否则 memory */
        AUTO,
        /** 内存（单机/测试：重启丢记录——键记忆随进程消亡） */
        MEMORY,
        /** JDBC（MySQL 方言默认；第三方 MySQL 兼容库可换 {@link IdempotentSql} 方言 Bean） */
        JDBC,
        /** MyBatis（消费方 classpath 自带 mybatis） */
        MYBATIS,
        /** Redis（缓存形态：Lua 原生四操作 + TTL 即键记忆窗口；需 StringRedisTemplate） */
        REDIS
    }

    /** 组件总开关（false 时自动装配整体让位） */
    private boolean enabled = true;

    /** 存储形态 */
    private Store store = Store.AUTO;

    /** 幂等表名（白名单 [A-Za-z0-9_]；表形态用） */
    private String tableName = "idempotent_record";

    /** Redis 键前缀（redis 形态用；与 lock 的 starsite:lock: 同命名法） */
    private String redisKeyPrefix = "starsite:idem:";

    /** 键记忆窗口时长（时）——DONE 行过期即"键遗忘"重新执行（Stripe 24h 同款语义） */
    private long keyTtlHours = 24;

    /** 在飞等待窗口（毫秒）——同键他方执行中时本请求的等待上限，超时 CONFLICT */
    private long waitMillis = 3000;

    /** 等待轮询间隔（毫秒） */
    private long pollMillis = 100;

    /** PROCESSING 停滞阈值（秒）——gmt_modified 停滞超此即判崩溃遗留可回收（应大于动作最长耗时） */
    private long processingStaleSeconds = 120;

    /** 过期行清理看护开关（expire_at 过期行语义已死——清理纯表卫生，默认开） */
    private boolean retentionEnabled = true;

    /** 单轮清理批量上限（满批看护自动追赶轮询） */
    private int retentionBatchSize = 500;

    /** 清理看护间隔（分钟） */
    private long purgeIntervalMinutes = 60;

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
     * @param tableName 表名（白名单 [A-Za-z0-9_]）
     */
    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    /**
     * 获取 Redis 键前缀。
     *
     * @return 键前缀（redis 形态用）
     */
    public String getRedisKeyPrefix() {
        return redisKeyPrefix;
    }

    /**
     * 设置 Redis 键前缀。
     *
     * @param redisKeyPrefix 键前缀（多组件共库时隔离命名空间）
     */
    public void setRedisKeyPrefix(String redisKeyPrefix) {
        this.redisKeyPrefix = redisKeyPrefix;
    }

    /**
     * 获取键记忆窗口时长。
     *
     * @return 窗口时长（时）
     */
    public long getKeyTtlHours() {
        return keyTtlHours;
    }

    /**
     * 设置键记忆窗口时长。
     *
     * @param keyTtlHours 窗口时长（时，&gt;=1）
     */
    public void setKeyTtlHours(long keyTtlHours) {
        this.keyTtlHours = keyTtlHours;
    }

    /**
     * 获取在飞等待窗口。
     *
     * @return 等待窗口（毫秒）
     */
    public long getWaitMillis() {
        return waitMillis;
    }

    /**
     * 设置在飞等待窗口。
     *
     * @param waitMillis 等待窗口（毫秒，0=不等待在飞即 CONFLICT）
     */
    public void setWaitMillis(long waitMillis) {
        this.waitMillis = waitMillis;
    }

    /**
     * 获取等待轮询间隔。
     *
     * @return 轮询间隔（毫秒）
     */
    public long getPollMillis() {
        return pollMillis;
    }

    /**
     * 设置等待轮询间隔。
     *
     * @param pollMillis 轮询间隔（毫秒，&gt;=1）
     */
    public void setPollMillis(long pollMillis) {
        this.pollMillis = pollMillis;
    }

    /**
     * 获取 PROCESSING 停滞阈值。
     *
     * @return 阈值（秒）
     */
    public long getProcessingStaleSeconds() {
        return processingStaleSeconds;
    }

    /**
     * 设置 PROCESSING 停滞阈值。
     *
     * @param processingStaleSeconds 阈值（秒，应大于动作最长执行时长）
     */
    public void setProcessingStaleSeconds(long processingStaleSeconds) {
        this.processingStaleSeconds = processingStaleSeconds;
    }

    /**
     * 获取过期清理看护开关。
     *
     * @return true 启用看护
     */
    public boolean isRetentionEnabled() {
        return retentionEnabled;
    }

    /**
     * 设置过期清理看护开关。
     *
     * @param retentionEnabled 看护开关
     */
    public void setRetentionEnabled(boolean retentionEnabled) {
        this.retentionEnabled = retentionEnabled;
    }

    /**
     * 获取单轮清理批量上限。
     *
     * @return 批量上限
     */
    public int getRetentionBatchSize() {
        return retentionBatchSize;
    }

    /**
     * 设置单轮清理批量上限。
     *
     * @param retentionBatchSize 批量上限（&gt;=1）
     */
    public void setRetentionBatchSize(int retentionBatchSize) {
        this.retentionBatchSize = retentionBatchSize;
    }

    /**
     * 获取清理看护间隔。
     *
     * @return 间隔（分钟）
     */
    public long getPurgeIntervalMinutes() {
        return purgeIntervalMinutes;
    }

    /**
     * 设置清理看护间隔。
     *
     * @param purgeIntervalMinutes 间隔（分钟，&gt;=1）
     */
    public void setPurgeIntervalMinutes(long purgeIntervalMinutes) {
        this.purgeIntervalMinutes = purgeIntervalMinutes;
    }

    /**
     * 键记忆窗口换算 Duration（引擎装配用；下限 1 小时防误配 0 窗口）。
     *
     * @return 窗口时长
     */
    public Duration keyTtl() {
        return Duration.ofHours(Math.max(1, keyTtlHours));
    }

    /**
     * 在飞等待窗口换算 Duration（装配用）。
     *
     * @return 等待时长
     */
    public Duration waitTimeout() {
        return Duration.ofMillis(Math.max(0, waitMillis));
    }

    /**
     * PROCESSING 停滞阈值换算 Duration（装配用；下限 1 秒）。
     *
     * @return 阈值时长
     */
    public Duration processingStale() {
        return Duration.ofSeconds(Math.max(1, processingStaleSeconds));
    }

    /**
     * 清理看护间隔换算 Duration（装配用；下限 1 分钟）。
     *
     * @return 间隔时长
     */
    public Duration purgeInterval() {
        return Duration.ofMinutes(Math.max(1, purgeIntervalMinutes));
    }
}
