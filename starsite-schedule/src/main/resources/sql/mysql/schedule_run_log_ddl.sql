-- starsite-schedule 流水表生产 DDL（与 dialect/mysql/schedule-mysql.xml#createTable 同义；
-- 生产环境由迁移脚本管理时使用本文件，组件构造期 initDdl 建表幂等可并存）
-- 表规范（三组件统一铁律）：id 由 starsite-sequence 发号（禁 AUTO_INCREMENT）；
-- gmt_create/gmt_modified 标准时间列；高频路径全索引命中；error 详情列不进列表查询。
-- 双语言协议：状态枚举与 invest Python 侧一致（RUNNING/DONE/FAILED/SKIPPED_LOCKED/STALE）。

CREATE TABLE IF NOT EXISTS schedule_run_log (
  id            BIGINT       NOT NULL COMMENT '主键（starsite-sequence 组件生成）',
  task_name     VARCHAR(64)  NOT NULL COMMENT '任务名',
  lock_name     VARCHAR(128) NOT NULL COMMENT '锁名 sch:<任务名>（跨语言协议锚点）',
  trigger_time  DATETIME     NOT NULL COMMENT '计划触发时刻（misfire 判定基准）',
  started_at    DATETIME     NOT NULL COMMENT '实际开始时刻（悬挂回收判死锚）',
  finished_at   DATETIME     DEFAULT NULL COMMENT '终态时刻',
  host          VARCHAR(128) NOT NULL COMMENT '执行实例 hostname:pid',
  status        VARCHAR(16)  NOT NULL COMMENT 'RUNNING/DONE/FAILED/SKIPPED_LOCKED/STALE',
  duration_ms   BIGINT       DEFAULT NULL COMMENT '执行时长毫秒',
  error         VARCHAR(500) DEFAULT NULL COMMENT '失败摘要（详情列，不进列表查询）',
  gmt_create    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  gmt_modified  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (id),
  KEY idx_srl_task_time (task_name, trigger_time) COMMENT '最近流水/lastDone：任务+触发时刻',
  KEY idx_srl_status_started (status, started_at) COMMENT '悬挂回收：RUNNING+开始时刻'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='定时任务执行流水';
