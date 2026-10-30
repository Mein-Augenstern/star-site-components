# 后续规划

> [← 返回概览](../README.md)

## 已交付（2026-10 组件库建设轮）

- [x] async-command：批量执行器、Micrometer 指标、管理端鉴权、分布式调度三模式
- [x] star-site-backend 生产接入（发号 + AI 解读异步命令 + workerId 租约 + 定时任务分布式锁防重跑）
- [x] lock：可重入（存储端计数：DB hold_count 列 / Redis hash）、限时等待获取、行丢失自愈、
      通用表名 starsite_lock（去调度绑定）
- [x] 三组件 SQL/脚本配置外置（Mapper XML + 方言 XML + Lua 文件）、索引完备性门禁、
      列表/详情分列（payload 大列不进列表）

## 待办（按优先级）

- [ ] sequence：Redis 租约实现（无 MySQL 场景）、K8s StatefulSet 序号直取
- [ ] sequence：运维能力（SequenceAdminClient：listWorkerIds 巡检 workerId 分配 /
      getCurrentPoolValue 查全局池计数 / resetWorkerId 强制归还卡死的 workerId）——
      与 lock 的 LockAdminClient 同模式（独立接口 + admin-enabled 开关）
- [ ] lock：看门狗自动续期（长任务持锁跨 TTL 场景——自动续期线程/租约延长，替代手工多次 tryLock 续期）
- [ ] async-command：深分页游标化（selectPage 的 OFFSET → `WHERE id < lastId` keyset，配套
      admin 端点 lastId 参数——OFFSET 深翻页线性变慢，2026-10 SQL 审计挂账）
- [ ] async-command：reclaimStale 专用索引 (status, gmt_modified)——悬挂 RUNNING 常态化
      （表百万行级）时再上，当前靠 idx_status_next 前缀回表够用
- [ ] lock：TTL 到期判定的跨 Pod 时钟偏差治理——当前各 Pod 应用时钟判过期（依赖 NTP 对齐），
      备选 DB 时钟判定但破坏假时钟可测性，需权衡后定案
- [ ] 0.1.0 正式版发布：当前 0.1.0-SNAPSHOT 仅本地 m2（backend CI 靠 checkout 组件仓装包），
      稳定后 tag + Release Notes，评估发布到托管仓库（GitHub Packages / 中央仓）
