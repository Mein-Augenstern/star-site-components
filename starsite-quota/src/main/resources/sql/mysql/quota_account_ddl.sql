-- starsite-quota 配额表生产 DDL（与 dialect/mysql/quota-mysql.xml#createTable 同义；
-- 生产环境由迁移脚本管理时使用本文件，组件构造期 initDdl 建表幂等可并存）
-- 表规范（组件库统一铁律）：id 由 starsite-sequence 发号（禁 AUTO_INCREMENT）；
-- gmt_create/gmt_modified 标准时间列；全部操作 quota_key 点查命中 uk；
-- 不变式 occupied+frozen<=total 由五原子操作的条件 UPDATE 语义保证。

CREATE TABLE IF NOT EXISTS quota_account (
  id            BIGINT       NOT NULL COMMENT '主键（starsite-sequence 组件生成）',
  quota_key     VARCHAR(128) NOT NULL COMMENT '配额键（业务自定——额度/库存/席位/保证金）',
  total_amount  BIGINT       NOT NULL COMMENT '总量（单位业务自定：分/件/个）',
  occupied      BIGINT       NOT NULL DEFAULT 0 COMMENT '已占用',
  frozen        BIGINT       NOT NULL DEFAULT 0 COMMENT '冻结中',
  gmt_create    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  gmt_modified  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_quota_key (quota_key) COMMENT '建户互斥与全部操作点查锚'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='余额型配额账户';
