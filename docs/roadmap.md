# 后续规划

> [← 返回概览](../README.md)

## 已交付（2026-10 组件库建设轮）

- [x] async-command：批量执行器、Micrometer 指标、管理端鉴权、分布式调度三模式
- [x] star-site-backend 生产接入（发号 + AI 解读异步命令 + workerId 租约 + 定时任务分布式锁防重跑）
- [x] lock：可重入（存储端计数：DB hold_count 列 / Redis hash）、限时等待获取、行丢失自愈、
      通用表名 starsite_lock（去调度绑定）
- [x] 三组件 SQL/脚本配置外置（Mapper XML + 方言 XML + Lua 文件）、索引完备性门禁、
      列表/详情分列（payload 大列不进列表）
- [x] schedule：注解式定时任务恰一执行（自研 cron / 复用 lock 争锁 / run_log 流水 /
      misfire 策略 / UTC 陷阱产品化默认时区）——2026-11-02 v0.1
- [x] idempotent：API 幂等键（Stripe 同款：恰一执行 + 首次结果回放 / 失败释放键 /
      崩溃惰性自愈 / 代际令牌守卫 / retention 默认开）——2026-11-12 v0.1；
      +redis 形态（Lua 四操作 + TTL 键记忆窗口）与第三方存储扩展指南——2026-11-13
- [x] 全组件扩展口子统一（docs/extension.md）：三层接入通道 + 五组件扩展点清单 +
      sequence WorkerIdAllocator 让位拉齐（所有形态 Bean 优先）——2026-11-13
- [x] quota：余额型配额（六号组件——五原子操作 + 不变式 occupied+frozen≤total 的
      防超额通用内核，信贷"防超贷"剥领域后的抽象； jdbc/memory/redis/mybatis 四形态 +
      四载体条件对齐测试）——2026-11-14 v0.1
- [x] tuner：线程池参数预案与治理（七号组件——预案/窗口/绑定/审计，窗口生效=纯时间
      函数多实例收敛；信贷"参数预案平台"剥领域后的抽象；无配置中心团队的 DynamicTp
      替代）——2026-11-14 v0.1

## 待办（按优先级）

### 分布式存储三阶段（[方案设计](distributed-sharding.md)，2026-10-31 立项）

- [x] **Phase 1（0.2.2 已实装验证）**：载体定为 ShardingSphere Proxy 5.4.1（嵌入式因三重
      classpath 冲突被否，OBS-109）——组件零改动 + reclaim 两步法演进；1库×10表全量验收
      （均衡<10%/互斥等价/租约正常/fan-out 追赶清零/Gateway 0 错误），吞吐代价 -15~30%
- [ ] **0.4.0 Phase 2**：shard-affinity 分片亲和（控制面心跳注册表/故障接管）·
      admin fan-out 聚合 + 游标分页 · 百库百表压测 · route-by:biz · 扩容再平衡专项
- [ ] **0.5.0 Phase 3**：OceanBase 方言包（分区表 DDL + round-robin 认领分区裁剪）·
      OB 实机验收 · Redis hash tag 全量落地 · 分布式部署章节进 README

### 既有挂账

- [ ] **Maven Central 发布**（BUG-010，最高优先）：组件未发仓导致 backend Docker 全新构建断链
- [ ] lock：fail-fast 可选开关（OBS-107——DB 故障恢复瞬间互斥击穿窗口的保守选项）
- [ ] sequence：K8s StatefulSet 序号直取（有状态集场景免租约）
- [ ] sequence：运维能力（SequenceAdminClient——与 LockAdminClient 同模式）
- [ ] async-command：深分页游标化（与 Phase 2 admin 聚合分页一并交付）
- [ ] async-command：reclaimStale 专用索引 (status, gmt_modified)——表百万行级时再上
- [ ] lock：TTL 到期判定的跨 Pod 时钟偏差治理（依赖 NTP 对齐 vs DB 时钟判定的可测性权衡）
