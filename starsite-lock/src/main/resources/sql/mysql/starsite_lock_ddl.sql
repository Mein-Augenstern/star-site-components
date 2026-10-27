-- ============================================================
-- starsite-lock 组件建表 DDL（MySQL 8）
-- 使用方式：DBA 手工执行 / 复制到 Flyway/Liquibase / starter init-ddl 自动执行
-- 表名 starsite_lock：通用分布式锁（历史名 component_schedule_lock 已废弃——
-- 锁组件不绑定调度场景，凡需多实例互斥处皆可用）
-- 表规范：id 物理主键（starsite-sequence 组件生成，禁 AUTO_INCREMENT）
--         + lock_name 唯一键（互斥锚点）+ 标准时间列 gmt_create / gmt_modified
-- 种子行由组件首次争锁时自动补插（INSERT ... WHERE NOT EXISTS，id 走 sequence）
--
-- 索引清单（最小集，争锁/释放/查询全部点查，无扫描无慢 SQL）：
--   PRIMARY KEY (id)              —— 物理主键（种子行 id 由 sequence 组件发号）
--   UNIQUE KEY uk_starsite_lock_name (lock_name)
--                                 —— 互斥锚点：tryAcquire/release/findHolder 的 WHERE
--                                    全部命中此键；种子 INSERT 的 NOT EXISTS 子查询
--                                    与并发首抢的冲突判定也依赖它
-- ============================================================

CREATE TABLE IF NOT EXISTS starsite_lock (
  id           BIGINT       NOT NULL COMMENT '主键（starsite-sequence 组件生成）',
  lock_name    VARCHAR(64)  NOT NULL COMMENT '锁名（全应用唯一）',
  locked_by    VARCHAR(128) DEFAULT NULL COMMENT '当前持锁者标识(服务名@pid)',
  locked_until DATETIME(3)  DEFAULT NULL COMMENT '持锁到期时刻(TTL过后可被抢)',
  hold_count   INT          NOT NULL DEFAULT 0 COMMENT '重入持有层数（存储端权威，同 ownerId 跨实例共享）',
  gmt_create   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_starsite_lock_name (lock_name) COMMENT '互斥锚点：争锁/释放/查询全部点查此键'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='starsite-lock 组件分布式锁表';
