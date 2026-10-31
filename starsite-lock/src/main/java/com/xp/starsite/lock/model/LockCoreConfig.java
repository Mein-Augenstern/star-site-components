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

    /**
     * 锁表名（mysql 形态，默认 starsite_lock）。
     * 通用分布式锁——不绑定调度场景（历史默认 component_schedule_lock 已更名）。
     * 分片形态注记：表名即逻辑表名，经 ShardingSphere 按 lock_name 路由到
     * 物理分片表（同键恒定同片=互斥语义与单表等价，1库×10表实装验证 0.2.2）。
     */
    private String tableName = "starsite_lock";

    /**
     * 持锁者标识（空串 = starter 回填 spring.application.name@pid）。
     * 重入计数以 ownerId 为准——同标识的多次 tryLock 计为重入（hold_count 递增），
     * 故跨实例重入的前提是显式配相同 ownerId（默认 应用名@pid 每实例不同=不重入，
     * 这正是分布式互斥的预期语义）。
     */
    private String ownerId = "";

    /**
     * 持锁 TTL 毫秒（默认 30 秒；超时即让位——持锁者崩溃后其他实例最长等一个 TTL）。
     * 须大于业务临界区时长并留时钟偏差余量（跨 Pod 依赖 NTP 对齐）；开启看门狗
     * （watchdog-enabled）后 TTL 变为续期基准（每 TTL/3 自动续），长任务不再受此约束。
     */
    private long ttlMs = 30_000;

    /**
     * Redis key 前缀（redis 形态，默认 starsite:lock:）。
     * 最终 key = 前缀 + 锁名；Redis Cluster 下建议前缀含 hash tag（如
     * {@code starsite:lock:{app}:}）让同应用锁 key 聚簇单 slot——批量运维操作
     * （SCAN/批量释放）不被跨 slot 拒绝；单 key 操作不受影响。
     */
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
