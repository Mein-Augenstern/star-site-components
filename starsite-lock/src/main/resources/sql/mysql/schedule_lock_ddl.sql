-- ============================================================
-- starsite-lock 组件建表 DDL（MySQL 8）
-- 使用方式：DBA 手工执行 / 复制到 Flyway/Liquibase / starter init-ddl 自动执行
-- ============================================================

CREATE TABLE IF NOT EXISTS component_schedule_lock (
  lock_name    VARCHAR(64)  NOT NULL COMMENT '锁名（全应用唯一）',
  locked_by    VARCHAR(128) DEFAULT NULL COMMENT '当前持锁者标识(服务名@pid)',
  locked_until DATETIME(3)  DEFAULT NULL COMMENT '持锁到期时刻(TTL过后可被抢)',
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (lock_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组件分布式调度锁表';
