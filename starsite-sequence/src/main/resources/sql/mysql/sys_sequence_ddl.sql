-- ============================================================
-- starsite-sequence 组件建表 DDL（MySQL 8）
-- 使用方式：DBA 手工执行 / 复制到 Flyway/Liquibase / 组件自动建表(init-ddl=true)
-- 表规范：id 物理主键（__global__ 种子行固定 id=1 为自举保留值）
--         + service_name 唯一键（upsert 幂等锚点 + 等值命中）
-- ============================================================

CREATE TABLE IF NOT EXISTS sys_sequence (
  id           BIGINT      NOT NULL COMMENT '主键（审计行由发号器生成；__global__ 行固定为 1）',
  service_name VARCHAR(64) NOT NULL COMMENT '服务名（__global__=全局池计数行，其余为实例占用记录）',
  worker_id    INT NOT NULL DEFAULT 0 COMMENT '该行机器位（全局池行=已分配到的最大值）',
  instance     VARCHAR(128) DEFAULT NULL COMMENT '最近占用实例标识',
  gmt_create   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_sequence_service_name (service_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='雪花 worker-id 分配序列表';

-- 全局池计数行种子（LAST_INSERT_ID 原子租约的锚点行；id=1 为组件自举保留值）
INSERT IGNORE INTO sys_sequence (id, service_name, worker_id, instance)
VALUES (1, '__global__', 0, 'pool');
