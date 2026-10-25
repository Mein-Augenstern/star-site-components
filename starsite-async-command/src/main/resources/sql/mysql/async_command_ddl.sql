-- ============================================================
-- starsite-async-command 组件建表 DDL（MySQL 8）
-- 使用方式（三选一）：
--   1. 组件 init-ddl=true 时自动执行（IF NOT EXISTS 幂等）
--   2. DBA 手工执行本文件
--   3. 复制到 Flyway/Liquibase 迁移脚本目录
-- 注意：表名默认 async_command，如需改表名请全局替换
-- ============================================================

CREATE TABLE IF NOT EXISTS async_command (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  cmd_type    VARCHAR(64)  NOT NULL COMMENT '命令类型=执行器路由键',
  biz_key     VARCHAR(128) DEFAULT '' COMMENT '业务键(幂等/追踪)',
  payload     TEXT         DEFAULT NULL COMMENT '命令载荷 JSON',
  status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/DEAD',
  max_retry   INT          NOT NULL DEFAULT 5 COMMENT '重试上限',
  retry_count INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
  next_run_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '到期时间(延迟队列锚点)',
  last_error  VARCHAR(500) DEFAULT NULL COMMENT '最后失败原因(截断到480字符)',
  owner       VARCHAR(64)  DEFAULT '' COMMENT '提交方服务名',
  claim_token VARCHAR(64)  DEFAULT NULL COMMENT '调度认领令牌(认领瞬间生成，完成后清空)',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_status_next (status, next_run_at) COMMENT '认领查询：按状态+到期扫',
  KEY idx_type_biz (cmd_type, biz_key) COMMENT '幂等检查：同类型同业务键计数'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='异步命令表';

-- LEADER 调度模式的分布式锁表（schedule-mode=leader 时使用；competing/external 模式不需要）
CREATE TABLE IF NOT EXISTS component_schedule_lock (
  lock_name    VARCHAR(64)  NOT NULL COMMENT '锁名（组件内固定，如 async-cmd-scheduler）',
  locked_by    VARCHAR(128) DEFAULT NULL COMMENT '当前持锁者标识(服务名@pid)',
  locked_until DATETIME     DEFAULT NULL COMMENT '持锁到期时刻(TTL过后可被抢)',
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (lock_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组件分布式调度锁表';

-- 初始化锁表种子行（LEADER 模式争锁的 UPDATE 需要行存在）
INSERT IGNORE INTO component_schedule_lock (lock_name, locked_by, locked_until)
VALUES ('async-cmd-scheduler', NULL, NULL);
