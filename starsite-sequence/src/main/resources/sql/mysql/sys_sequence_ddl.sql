-- ============================================================
-- starsite-sequence 组件建表 DDL（MySQL 8）
-- 使用方式：DBA 手工执行 / 复制到 Flyway/Liquibase / 组件自动建表(init-ddl=true)
-- ============================================================

CREATE TABLE IF NOT EXISTS sys_sequence (
  service_name VARCHAR(64) NOT NULL COMMENT '服务名（__global__=全局池计数行，其余为实例占用记录）',
  worker_id    INT NOT NULL DEFAULT 0 COMMENT '该行机器位（全局池行=已分配到的最大值）',
  instance     VARCHAR(128) DEFAULT NULL COMMENT '最近占用实例标识',
  updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (service_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='雪花 worker-id 分配序列表';

-- 全局池计数行种子（LAST_INSERT_ID 原子租约的锚点行）
INSERT IGNORE INTO sys_sequence (service_name, worker_id, instance)
VALUES ('__global__', 0, 'pool');
