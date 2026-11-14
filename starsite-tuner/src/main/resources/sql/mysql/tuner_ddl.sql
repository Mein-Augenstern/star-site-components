-- starsite-tuner 治理三表生产 DDL（与 dialect/mysql/tuner-mysql.xml 同义；
-- 组件构造期 initDdl 建表幂等可并存）
-- 表规范：id 由 starsite-sequence 发号（禁 AUTO_INCREMENT）；gmt 两列标准时间。

CREATE TABLE IF NOT EXISTS tuner_preset (
  id                 BIGINT       NOT NULL COMMENT '主键（starsite-sequence 组件生成）',
  preset_name        VARCHAR(64)  NOT NULL COMMENT '预案名（repayday/daily/batch-window）',
  core_size          INT          NOT NULL COMMENT '核心线程数',
  max_size           INT          NOT NULL COMMENT '最大线程数',
  keep_alive_seconds INT          NOT NULL COMMENT '空闲保活秒',
  gmt_create         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  gmt_modified       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_tuner_preset_name (preset_name) COMMENT '按名幂等 upsert 锚'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='线程池参数预案';

CREATE TABLE IF NOT EXISTS tuner_window (
  id                 BIGINT       NOT NULL COMMENT '主键（starsite-sequence 组件生成）',
  pool_name          VARCHAR(128) NOT NULL COMMENT '受管池名',
  preset_name        VARCHAR(64)  NOT NULL COMMENT '窗口内应用的预案名',
  start_cron         VARCHAR(64)  NOT NULL COMMENT '窗口开始 cron（6 字段）',
  end_cron           VARCHAR(64)  NOT NULL COMMENT '窗口结束 cron',
  gmt_create         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  gmt_modified       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (id),
  KEY idx_tw_pool (pool_name) COMMENT '按池查窗口'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='参数窗口（预案按时段生效）';

CREATE TABLE IF NOT EXISTS tuner_binding (
  id                 BIGINT       NOT NULL COMMENT '主键（starsite-sequence 组件生成）',
  pool_name          VARCHAR(128) NOT NULL COMMENT '受管池名',
  preset_name        VARCHAR(64)  NOT NULL COMMENT '默认预案名',
  gmt_create         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  gmt_modified       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_tb_pool (pool_name) COMMENT '按池幂等 upsert 锚'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='池默认预案绑定';

CREATE TABLE IF NOT EXISTS tuner_audit (
  id                 BIGINT       NOT NULL COMMENT '主键（starsite-sequence 组件生成）',
  occurred_at        DATETIME     NOT NULL COMMENT '变更时刻',
  host               VARCHAR(128) NOT NULL COMMENT '实例 hostname:pid（per-host 真实账面变化）',
  pool_name          VARCHAR(128) NOT NULL COMMENT '受管池名',
  preset_name        VARCHAR(64)  NOT NULL COMMENT '生效预案名',
  source             VARCHAR(16)  NOT NULL COMMENT '触发源 SCHEDULE/MANUAL/STARTUP',
  old_core           INT          NOT NULL COMMENT '旧核心数',
  old_max            INT          NOT NULL COMMENT '旧最大数',
  old_keep           INT          NOT NULL COMMENT '旧保活秒',
  new_core           INT          NOT NULL COMMENT '新核心数',
  new_max            INT          NOT NULL COMMENT '新最大数',
  new_keep           INT          NOT NULL COMMENT '新保活秒',
  gmt_create         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  gmt_modified       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (id),
  KEY idx_ta_pool_time (pool_name, occurred_at) COMMENT '按池查变更史'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='参数变更审计';
