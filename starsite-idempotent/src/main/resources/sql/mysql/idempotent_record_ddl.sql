-- starsite-idempotent 幂等表生产 DDL（与 dialect/mysql/idempotent-mysql.xml#createTable 同义；
-- 生产环境由迁移脚本管理时使用本文件，组件构造期 initDdl 建表幂等可并存）
-- 表规范（组件库统一铁律）：id 由 starsite-sequence 发号（禁 AUTO_INCREMENT）；
-- gmt_create/gmt_modified 标准时间列；高频路径全索引命中（认领/点查走 uk，清理走 expire 索引）；
-- result/error 详情列不进列表查询（本组件无列表查询，点查详情全列）。

CREATE TABLE IF NOT EXISTS idempotent_record (
  id            BIGINT        NOT NULL COMMENT '主键（starsite-sequence 组件生成）',
  scope         VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '键作用域（隔离不同业务的同名键）',
  idem_key      VARCHAR(128)  NOT NULL COMMENT '幂等键（业务提供——orderId/请求 Idempotency-Key 头）',
  status        VARCHAR(16)   NOT NULL COMMENT 'PROCESSING/DONE/FAILED',
  result        TEXT          COMMENT '回放载荷（仅 DONE 有值——首次成功结果的序列化形态）',
  error         VARCHAR(500)  COMMENT '失败摘要（仅 FAILED 有值，类型+消息截断）',
  expire_at     DATETIME      NOT NULL COMMENT '键记忆窗口边界（认领时刻+key-ttl；过期即键遗忘）',
  finished_at   DATETIME      DEFAULT NULL COMMENT '终态时刻',
  host          VARCHAR(128)  NOT NULL COMMENT '认领实例 hostname:pid',
  gmt_create    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  gmt_modified  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间（PROCESSING 活跃信号——停滞判崩溃遗留）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem_scope_key (scope, idem_key) COMMENT '认领互斥：INSERT 冲突即他方持有',
  KEY idx_idem_expire (expire_at) COMMENT 'retention 清理：expire_at 过期圈定'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='API 幂等键记录';
