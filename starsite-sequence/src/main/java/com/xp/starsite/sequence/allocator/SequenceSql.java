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
package com.xp.starsite.sequence.allocator;

/**
 * sequence 表 SQL 方言抽象：把全部 SQL 语句从分配器实现中外提——语句集中可见、
 * 方言可替换（MySQL 默认实现；适配其他数据库/ORM 时实现本接口经
 * starter 让位注入即可，MyBatis 迁移时各语句可逐条对照搬入 mapper XML）。
 * <p>
 * 设计约定：表名占位 {@code {table}}；参数一律 {@code ?}；语句本身不含业务值。
 *
 * @author muyi
 * @date 2026/10/25
 */
public interface SequenceSql {

    /** 建表语句（幂等 IF NOT EXISTS）。 */
    String createTable();

    /** 播种全局池计数行（参数：GLOBAL_POOL_KEY, 'pool'）。 */
    String seedGlobalPool();

    /** 原子取号：LAST_INSERT_ID 技巧带回自增值（参数：instanceTag, GLOBAL_POOL_KEY）。 */
    String takeFromGlobalPool();

    /** 会话内取回上次 LAST_INSERT_ID 值（无参）。 */
    String selectLastInsertId();

    /** 记录/更新服务实例占用审计行（参数：serviceName, workerId, instanceTag）。 */
    String recordOwnership();

    /** 回绕重置全局池（参数：resetTag, GLOBAL_POOL_KEY）。 */
    String resetGlobalPool();

    /**
     * MySQL 8 默认方言（语句与 0.1.0 版内置实现逐条等价，行为零变化）。
     */
    class MySql implements SequenceSql {

        @Override
        public String createTable() {
            return """
                    CREATE TABLE IF NOT EXISTS {table} (
                      service_name VARCHAR(64) NOT NULL COMMENT '服务名（__global__=全局池计数行，其余为实例占用记录）',
                      worker_id    INT NOT NULL DEFAULT 0 COMMENT '该行机器位（全局池行=已分配到的最大值）',
                      instance     VARCHAR(128) DEFAULT NULL COMMENT '最近占用实例标识',
                      updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      PRIMARY KEY (service_name)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='雪花 worker-id 分配序列表'""";
        }

        @Override
        public String seedGlobalPool() {
            return "INSERT IGNORE INTO {table} (service_name, worker_id, instance) VALUES (?, 0, ?)";
        }

        @Override
        public String takeFromGlobalPool() {
            // LAST_INSERT_ID(expr)：UPDATE 命中时把自增结果绑回会话，下一步 SELECT 可取
            return "UPDATE {table} SET worker_id = LAST_INSERT_ID(worker_id + 1), "
                    + "instance = ?, updated_at = NOW() WHERE service_name = ?";
        }

        @Override
        public String selectLastInsertId() {
            return "SELECT LAST_INSERT_ID()";
        }

        @Override
        public String recordOwnership() {
            return "INSERT INTO {table} (service_name, worker_id, instance, updated_at) "
                    + "VALUES (?, ?, ?, NOW()) "
                    + "ON DUPLICATE KEY UPDATE worker_id = VALUES(worker_id), "
                    + "instance = VALUES(instance), updated_at = NOW()";
        }

        @Override
        public String resetGlobalPool() {
            return "UPDATE {table} SET worker_id = 0, instance = ? WHERE service_name = ?";
        }
    }
}
