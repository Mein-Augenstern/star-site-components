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
package com.xp.starsite.lock.model;


/**
 * 分布式锁配置（core 纯载体）——starter 的 Properties 继承本类并追加装配形态项。
 * 参数含义见 README「全量配置」表。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class LockCoreConfig {

    /** 锁表名（DB 模式）。通用分布式锁——不绑定调度场景（历史默认 component_schedule_lock 已更名） */
    private String tableName = "starsite_lock";

    /** 持锁者标识（starter 默认取 spring.application.name@pid）。 */
    private String ownerId = "";

    /** 锁 TTL 毫秒（超时自动释放；应大于业务持有时长）。 */
    private long ttlMs = 30_000;

    /** Redis key 前缀（redis 模式）。 */
    private String redisKeyPrefix = "starsite:lock:";

    /** 读取锁表名。@return 锁表名（仅字母数字下划线） */
    public String getTableName() { return tableName; }

    /** 设置锁表名。@param tableName 锁表名 */
    public void setTableName(String tableName) { this.tableName = tableName; }

    /** 读取持锁者标识。@return ownerId（空串时 starter 回填 应用名@pid） */
    public String getOwnerId() { return ownerId; }

    /** 设置持锁者标识。@param ownerId 持锁者标识 */
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    /** 读取锁 TTL。@return TTL 毫秒 */
    public long getTtlMs() { return ttlMs; }

    /** 设置锁 TTL。@param ttlMs TTL 毫秒（应大于业务持有时长） */
    public void setTtlMs(long ttlMs) { this.ttlMs = ttlMs; }

    /** 读取 Redis key 前缀。@return 前缀（redis 模式） */
    public String getRedisKeyPrefix() { return redisKeyPrefix; }

    /** 设置 Redis key 前缀。@param redisKeyPrefix 前缀 */
    public void setRedisKeyPrefix(String redisKeyPrefix) { this.redisKeyPrefix = redisKeyPrefix; }
}
