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
package com.xp.starsite.asynccmd.store;

/**
 * 命令表 SQL 方言抽象：把全部 SQL 语句从存储实现中外提——语句集中可见、
 * 方言可替换（MySQL 默认实现；适配其他数据库/ORM 时实现本接口并经
 * starter 的 @ConditionalOnMissingBean 让位注入即可）。
 * <p>
 * 设计约定：
 * <ul>
 *   <li>表名占位统一为 {@code {table}}（由存储层替换，表名经白名单校验防注入）</li>
 *   <li>参数一律 {@code ?} 占位符，值由调用方绑定——语句本身不含任何业务值</li>
 *   <li>MyBatis 适配示例：把本接口各语句放入 mapper XML 的
 *       {@code <sql id="...">}</code> 片段，用 {@code ${table}} 传表名、
 *       {@code #{...}} 传值即可逐条对照迁移</li>
 * </ul>
 *
 * @author muyi
 * @date 2026/10/25
 */
public interface AsyncCommandSql {

    /** 建表语句（幂等 IF NOT EXISTS；{table} 为表名占位）。 */
    String createTable();

    /** 新建命令（参数：cmd_type, biz_key, payload, status, max_retry, retry_count, next_run_at, owner；自增 id 回填）。 */
    String insert();

    /** 按主键查询。 */
    String findById();

    /** 幂等检查：同 type+key 未完成（PENDING/RUNNING）计数。 */
    String countUnfinished();

    /** 批量令牌认领第 1 步（参数：token, now, now, limit；MySQL 无 UPDATE..RETURNING 的两步法）。 */
    String claim();

    /** 批量令牌认领第 2 步：按令牌取回。 */
    String findByToken();

    /** 按 id 原子认领（IMMEDIATE 模式，参数：token, id；认领条数 &gt; 0 即成功）。 */
    String claimById();

    /** 崩溃回收：悬挂 RUNNING 回 PENDING（参数：staleAfterSeconds, limit；用库时钟判"多久没动静"）。 */
    String reclaimStale();

    /** 状态回写（status, claimToken, retryCount, nextRunAt, lastError, id）。 */
    String updateState();

    /** 按状态计数。 */
    String countByStatus();

    /** 到期积压计数（参数 now）。 */
    String countBacklogDue();

    /** 近 N 条（id 倒序）。 */
    String selectRecent();

    /** 管控分页计数（status/cmdType 可选过滤，动态拼接由存储层负责）。 */
    String countPage();

    /** 管控分页查询（id 倒序 + LIMIT/OFFSET）。 */
    String selectPage();

    /**
     * MySQL 8 默认方言（语句与 0.1.0 版内置实现逐条等价，行为零变化）。
     */
    class MySql implements AsyncCommandSql {

        @Override
        public String createTable() {
            return """
                    CREATE TABLE IF NOT EXISTS {table} (
                      id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                      cmd_type    VARCHAR(64)  NOT NULL COMMENT '命令类型=执行器路由键',
                      biz_key     VARCHAR(128) DEFAULT '' COMMENT '业务键(幂等/追踪)',
                      payload     TEXT         DEFAULT NULL COMMENT '命令载荷 JSON',
                      status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/DEAD',
                      max_retry   INT          NOT NULL DEFAULT 5,
                      retry_count INT          NOT NULL DEFAULT 0,
                      next_run_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '到期时间(延迟队列锚点)',
                      last_error  VARCHAR(500) DEFAULT NULL,
                      owner       VARCHAR(64)  DEFAULT '' COMMENT '提交方服务名',
                      claim_token VARCHAR(64)  DEFAULT NULL COMMENT '调度认领令牌',
                      created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      PRIMARY KEY (id),
                      KEY idx_status_next (status, next_run_at),
                      KEY idx_type_biz (cmd_type, biz_key)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='异步命令'""";
        }

        @Override
        public String insert() {
            return "INSERT INTO {table} (cmd_type, biz_key, payload, status, max_retry, retry_count, "
                    + "next_run_at, owner) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        }

        @Override
        public String findById() {
            return "SELECT * FROM {table} WHERE id = ?";
        }

        @Override
        public String countUnfinished() {
            return "SELECT COUNT(*) FROM {table} WHERE cmd_type = ? AND biz_key = ? "
                    + "AND status IN ('PENDING', 'RUNNING')";
        }

        @Override
        public String claim() {
            // 派生表 {t} 绕 MySQL ERROR 1093（UPDATE 不能直接改 IN 子查询引用的同表）
            return "UPDATE {table} SET status = 'RUNNING', claim_token = ?, updated_at = NOW() "
                    + "WHERE status = 'PENDING' AND next_run_at <= ? "
                    + "AND id IN (SELECT id FROM (SELECT id FROM {table} "
                    + "WHERE status = 'PENDING' AND next_run_at <= ? ORDER BY id LIMIT ?) t)";
        }

        @Override
        public String findByToken() {
            return "SELECT * FROM {table} WHERE claim_token = ? ORDER BY id";
        }

        @Override
        public String claimById() {
            return "UPDATE {table} SET status = 'RUNNING', claim_token = ?, updated_at = NOW() "
                    + "WHERE id = ? AND status = 'PENDING' AND next_run_at <= NOW()";
        }

        @Override
        public String reclaimStale() {
            return "UPDATE {table} SET status = 'PENDING', claim_token = NULL, updated_at = NOW() "
                    + "WHERE status = 'RUNNING' AND updated_at < NOW() - INTERVAL ? SECOND LIMIT ?";
        }

        @Override
        public String updateState() {
            return "UPDATE {table} SET status = ?, claim_token = ?, retry_count = ?, "
                    + "next_run_at = ?, last_error = ?, updated_at = NOW() WHERE id = ?";
        }

        @Override
        public String countByStatus() {
            return "SELECT COUNT(*) FROM {table} WHERE status = ?";
        }

        @Override
        public String countBacklogDue() {
            return "SELECT COUNT(*) FROM {table} WHERE status = 'PENDING' AND next_run_at <= ?";
        }

        @Override
        public String selectRecent() {
            return "SELECT * FROM {table} ORDER BY id DESC LIMIT ?";
        }

        @Override
        public String countPage() {
            return "SELECT COUNT(*) FROM {table} WHERE 1 = 1";
        }

        @Override
        public String selectPage() {
            return "SELECT * FROM {table} WHERE 1 = 1";
        }
    }
}
