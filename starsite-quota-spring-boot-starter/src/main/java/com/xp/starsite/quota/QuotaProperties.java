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
package com.xp.starsite.quota;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 配置项（starsite.quota.*）。
 *
 * <pre>
 * starsite.quota.enabled=true              # 组件总开关（false=完全让位）
 * starsite.quota.store=auto                # auto/memory/jdbc/mybatis/redis
 * starsite.quota.table-name=quota_account
 * starsite.quota.redis-key-prefix=starsite:quota:
 * </pre>
 *
 * @author muyi
 * @date 2026/11/14
 */
@ConfigurationProperties(prefix = "starsite.quota")
public class QuotaProperties {

    /** 存储形态 */
    public enum Store {
        /** 自动探测：显式 mybatis 优先（见 MyBatis 装配），有 DataSource 走 jdbc，否则 memory */
        AUTO,
        /** 内存（单机/测试：重启丢账面） */
        MEMORY,
        /** JDBC（MySQL 方言默认；第三方 MySQL 兼容库可换 QuotaSql 方言 Bean） */
        JDBC,
        /** MyBatis（消费方 classpath 自带 mybatis） */
        MYBATIS,
        /** Redis（Lua 条件更新；需 StringRedisTemplate） */
        REDIS
    }

    /** 组件总开关（false 时自动装配整体让位） */
    private boolean enabled = true;

    /** 存储形态 */
    private Store store = Store.AUTO;

    /** 配额表名（白名单 [A-Za-z0-9_]；表形态用） */
    private String tableName = "quota_account";

    /** Redis 键前缀（redis 形态用；与 lock/idempotent 同命名法） */
    private String redisKeyPrefix = "starsite:quota:";

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
     * @return 键前缀
     */
    public String getRedisKeyPrefix() {
        return redisKeyPrefix;
    }

    /**
     * 设置 Redis 键前缀。
     *
     * @param redisKeyPrefix 键前缀（多组件共库隔离命名空间）
     */
    public void setRedisKeyPrefix(String redisKeyPrefix) {
        this.redisKeyPrefix = redisKeyPrefix;
    }
}
