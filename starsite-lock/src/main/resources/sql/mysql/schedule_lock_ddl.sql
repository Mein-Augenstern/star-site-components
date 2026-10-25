-- ============================================================
-- starsite-lock 组件建表 DDL（MySQL 8）
-- 使用方式：DBA 手工执行 / 复制到 Flyway/Liquibase / starter init-ddl 自动执行
-- 表规范：id 物理主键（starsite-sequence 组件生成，禁 AUTO_INCREMENT）
--         + lock_name 唯一键（互斥锚点）+ 标准时间列 gmt_create / gmt_modified
-- 种子行由组件首次争锁时自动补插（INSERT ... WHERE NOT EXISTS，id 走 sequence）
-- ============================================================

CREATE TABLE IF NOT EXISTS component_schedule_lock (
  id           BIGINT       NOT NULL COMMENT '主键（starsite-sequence 组件生成）',
  lock_name    VARCHAR(64)  NOT NULL COMMENT '锁名（全应用唯一）',
  locked_by    VARCHAR(128) DEFAULT NULL COMMENT '当前持锁者标识(服务名@pid)',
  locked_until DATETIME(3)  DEFAULT NULL COMMENT '持锁到期时刻(TTL过后可被抢)',
  gmt_create   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_schedule_lock_name (lock_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组件分布式调度锁表';
