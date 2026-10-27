-- ============================================================
-- starsite-async-command 组件建表 DDL（MySQL 8）
-- 使用方式（三选一）：
--   1. 组件 init-ddl=true 时自动执行（IF NOT EXISTS 幂等）
--   2. DBA 手工执行本文件
--   3. 复制到 Flyway/Liquibase 迁移脚本目录
-- 注意：表名默认 async_command，如需改表名请全局替换
-- 表规范：主键 id 由 starsite-sequence 组件生成（禁 AUTO_INCREMENT）；
--         标准时间列 gmt_create / gmt_modified（与代码 DDL 完全一致）
-- ============================================================

CREATE TABLE IF NOT EXISTS async_command (
  id           BIGINT UNSIGNED NOT NULL COMMENT '主键（starsite-sequence 组件生成）',
  cmd_type     VARCHAR(64)  NOT NULL COMMENT '命令类型=执行器路由键',
  biz_key      VARCHAR(128) DEFAULT '' COMMENT '业务键(幂等/追踪)',
  payload      TEXT         DEFAULT NULL COMMENT '命令载荷 JSON',
  status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/DEAD',
  max_retry    INT          NOT NULL DEFAULT 5 COMMENT '重试上限',
  retry_count  INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
  next_run_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '到期时间(延迟队列锚点)',
  last_error   VARCHAR(500) DEFAULT NULL COMMENT '最后失败原因(截断到480字符)',
  owner        VARCHAR(64)  DEFAULT '' COMMENT '提交方服务名',
  claim_token  VARCHAR(64)  DEFAULT NULL COMMENT '调度认领令牌(认领瞬间生成，完成后清空)',
  gmt_create   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  gmt_modified DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (id),
  KEY idx_status_next (status, next_run_at) COMMENT '认领查询：按状态+到期扫',
  KEY idx_type_biz (cmd_type, biz_key) COMMENT '幂等检查：同类型同业务键计数',
  KEY idx_claim_token (claim_token) COMMENT '认领取回：按令牌取本批命令（非唯一——一批共用令牌）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='异步命令表';

-- LEADER 调度模式的分布式锁表（schedule-mode=leader 时使用；competing/external 模式不需要）
-- 索引：PRIMARY KEY (id) 物理主键（sequence 发号）；uk_starsite_lock_name 互斥锚点（争锁/释放/查询全点查）
-- 表规范：id 物理主键（sequence 生成）+ lock_name 唯一键（互斥锚点）+ 标准时间列
CREATE TABLE IF NOT EXISTS starsite_lock (
  id           BIGINT       NOT NULL COMMENT '主键（starsite-sequence 组件生成）',
  lock_name    VARCHAR(64)  NOT NULL COMMENT '锁名（组件内固定，如 async-cmd-scheduler）',
  locked_by    VARCHAR(128) DEFAULT NULL COMMENT '当前持锁者标识(服务名@pid)',
  locked_until DATETIME     DEFAULT NULL COMMENT '持锁到期时刻(TTL过后可被抢)',
  hold_count   INT          NOT NULL DEFAULT 0 COMMENT '重入持有层数（存储端权威）',
  gmt_create   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_starsite_lock_name (lock_name) COMMENT '互斥锚点：争锁/释放/查询全部点查此键'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组件分布式调度锁表';
