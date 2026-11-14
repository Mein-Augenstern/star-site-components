# 扩展指南（公司内部落地）

> [← 返回概览](../README.md) · 适用版本：0.2.3+（七组件）
>
> 这套组件库在公司内部使用时必然遇到适配问题：自建存储（TBase/OceanBase/Tair）、
> 内部平台（鉴权/监控/机器位管理）、私有部署形态。本文是**全部扩展口子的权威清单**——
> 设计原则：能用配置解决的不写代码；要写代码的只实现小接口 + 注册 Bean，组件零改动。

## 0. 总原则：Bean 让位机制（一条铁律）

**应用注册同类型 Bean，自动装配整体让位**——全组件统一（`@ConditionalOnMissingBean`
全量覆盖，见各 starter）。扩展的常规姿势只有三步：

```java
@Bean
MyDialect myDialect() { return new MyDialect(); }   // ① 实现接口 ② 注册 Bean ③ 完事
```

配套开关：`starsite.<组件>.enabled=false` 完全让位（引 jar 但不用）；core 模块零
Spring Boot 依赖——非 Boot 应用可手工 `new` 组装（各 core 的门面类是唯一入口）。

## 1. 三层接入通道（按改动量从小到大选）

| 层 | 适用场景 | 做法 | 改动量 |
|---|---|---|---|
| **L1 配置换连** | MySQL 兼容库（OceanBase MySQL 模式 / TBase MySQL 模式） | 连接串指过去；表名/Redis 前缀/键名全可配 | **零代码** |
| **L2 方言替换** | 类 SQL 但语法有差异（TBase PG 模式 / GaussDB / Oracle） | 实现该组件的 `XxxSql` 方言接口注册 Bean——store 自动换方言运行 | 一个类 |
| **L3 SPI 实现** | 完全异构存储（Tair/自研 KV/内部锁服务/内部平台） | 实现该组件的 SPI 注册 Bean——引擎/注解/看护/健康全链路复用 | 一个类 |

> L2 方言接口清单：`IdempotentSql` / `AsyncCommandSql` / `ScheduleRunLogSql` / `SequenceSql`
> ——语句全部外置（Mapper XML / dialect XML / Lua），Java 零 SQL 字符串的架构本身
> 就是为换方言准备的（design.md D5）。

## 2. 七组件扩展点清单

### sequence（雪花 ID）

| 扩展点 | 接口 | 场景 | 硬契约 |
|---|---|---|---|
| 机器位分配 | `WorkerIdAllocator` | 内部平台/ZK/K8s StatefulSet 序号直取（免租约） | **同部署平台内分配结果互不冲突**（启动期调用一次） |
| 租约方言 | `SequenceSql` | PG 系库的租约/审计语句（`ON CONFLICT` 替代 upsert 等） | 语句语义与 MySQL 版逐条对齐 |
| 发号器整体 | `StarsiteIdGenerator` | 完全自备发号（其余组件依赖此接口发主键） | `next()` 单调递增且全局唯一 |

> 自定义 `WorkerIdAllocator` Bean 在**所有形态**（standalone/mysql/redis）优先于内置
> 构建——0.2.3+ 拉齐（此前仅 mysql+mybatis 路径让位）。

### async-command（异步命令）

| 扩展点 | 接口 | 场景 | 硬契约 |
|---|---|---|---|
| 存储 | `AsyncCommandStore` | 自建队列存储 | 认领（两步令牌法）/回收/终态回写**原子** |
| 方言 | `AsyncCommandSql` | PG 系库 | 语句语义对齐（认领三件套索引命中） |
| 执行器 | `AsyncCommandExecutor` / `AsyncCommandBatchExecutor` | 业务命令处理（本来就是业务 SPI） | type 唯一；异常=重试，超限 DEAD |
| 指标 | `AsyncCommandMetrics` | 接内部监控 SDK（无 micrometer 环境） | 计数语义四态对齐 |
| 管控鉴权 | `AsyncCommandAdminGuard` | 接内部鉴权体系（admin 端点三级鉴权的第一优先级） | 拦截语义：false 拒绝 |

### lock（分布式锁）

| 扩展点 | 接口 | 场景 | 硬契约 |
|---|---|---|---|
| 锁实现 | `LockClient` | Tair/etcd/内部锁服务 | `tryLock`/`unlock` **存储端原子**；TTL 语义（持有者崩溃后最长一个 TTL 让位）；可重入可选（async LEADER/schedule 依赖基础互斥即可） |
| 观测 | `LockAdminClient` | 锁表/锁键的巡检视图 | 只读 |

> 自定义 `LockClient` Bean 自动被 `WatchdogLockClient` 包装（watchdog-enabled 时）——
> 看门狗续期能力对扩展实现同样生效。

### schedule（定时任务恰一）

| 扩展点 | 接口 | 场景 | 硬契约 |
|---|---|---|---|
| 流水存储 | `RunLogStore` | 自建存储的执行流水 | start/skip/finish 语义对齐（列表分列 D6） |
| 方言 | `ScheduleRunLogSql` | PG 系库 | 语句语义对齐 |
| 触发器 | `Trigger`（core 内） | 内部调度平台接管触发（中心 tick 形态） | `nextTime` 单调推进 |
| 引擎/门面 | `ScheduleClient` / `ExactlyOnceRunner` | 整体替换调度行为 | 恰一语义（锁协议）不降级 |

> 恰一执行决策层复用 lock 组件——换锁实现（自定义 LockClient）即换 schedule 的互斥载体。

### idempotent（API 幂等键）

| 扩展点 | 接口 | 场景 | 硬契约 |
|---|---|---|---|
| 存储 | `IdempotentStore` | 自研 KV / 公司缓存平台 / Tair | **四操作存储端原子**：tryClaim / tryReclaim / complete / fail（多实例恰一执行的全部正确性来源）；观测方法（countByStatus/purgeExpired）是缺省实现，按能力选择覆写 |
| 方言 | `IdempotentSql` | PG 系库 | 9 语句语义对齐（uk 认领 / 三合一回收 / 双条件守卫） |

### quota（余额型配额）

| 扩展点 | 接口 | 场景 | 硬契约 |
|---|---|---|---|
| 存储 | `QuotaStore` | 自建存储/内部账务平台 | **五原子操作存储端原子**（条件判定+计数更新同一语句）——不变式 occupied+frozen≤total 的全部正确性来源；先读后写两步实现一律不合格 |
| 方言 | `QuotaSql` | PG 系库 | 条件片段语义对齐（不变式/充足性/降额保护） |

### tuner（参数预案与治理）

| 扩展点 | 接口 | 场景 | 硬契约 |
|---|---|---|---|
| 存储 | `TunerStore` | 自建配置源/内部治理平台 | upsert 按名幂等；versionStamp 写后必变（轮询短路依据） |
| 方言 | `TunerSql` | PG 系库 | ON DUPLICATE KEY → ON CONFLICT 等价改写 |

详见 [idempotent.md §5.5](idempotent.md)（含 TBase PG 方言与自研 KV 两个示例）。

## 3. 公司内落地检查清单

1. **依赖引入**：内部 Maven 仓镜像本仓库（或源码 fork 后改 groupId）；组件间依赖只有
   sequence（主键发号）——按需引入，不强买强卖
2. **命名空间隔离**：表名全可配（`table-name`）；Redis 前缀（lock `redis-key-prefix` /
   idempotent `redis-key-prefix`）——与公司共库其他业务隔离
3. **方言适配注意点**（PG 系）：`INSERT ... ON DUPLICATE KEY` → `ON CONFLICT`；
   `ON UPDATE CURRENT_TIMESTAMP` 不存在（gmt_modified 显式 SET）；`LIMIT ?` 参数绑定差异
4. **健康端点**：引 actuator 即自动上报（`starsite*` 前缀）；内部监控平台走各组件 Metrics
   SPI 或自定义 HealthIndicator 同名 Bean 替换
5. **快速失败不会咬人**：缺关键依赖（DataSource/Redis/MyBatis）启动期报错并给修复指引，
   不静默降级——CI 里跑一次启动冒烟即可暴露配置问题
6. **测试基线**：`mvn verify` 全绿 + 真库 IT（`-Pit`）；适配自家存储后建议补同构表单测
   （参考各组件 H2 测试的"同构表"手法）+ `TEST_MYSQL_URL` 逃生口接内部测试库

## 4. 三个常见适配示例

```java
// ① TBase（PG 模式）幂等方言：实现 IdempotentSql 注册 Bean（L2）
@Bean
IdempotentSql tbaseIdempotentDialect() {
    return new TbaseIdempotentSql();  // 9 个方法返回 PG 语句（ON CONFLICT / 显式 gmt_modified）
}
// starsite.idempotent.store=jdbc——jdbc store 自动采用
```

```java
// ② 内部锁服务接入：实现 LockClient（L3）
@Bean
LockClient companyLock() {
    return new TairLockClient(tairClient);  // tryLock=SET NX 语义原子；unlock 校验持有者
}
// async LEADER 选主、schedule 恰一执行、业务手工锁全部自动走它
```

```java
// ③ 内部平台发机器位：实现 WorkerIdAllocator（L3）
@Bean
WorkerIdAllocator platformWorkerId() {
    return new PlatformWorkerIdAllocator();  // 从内部平台/K8s StatefulSet 序号取，免 DB 租约
}
// 所有形态（standalone/mysql/redis）下优先于内置分配器
```

---

*设计决策记录见 [design.md](design.md) D12（扩展口子统一原则）；各组件使用手册见
docs/<组件>.md。扩展时拿不准语义契约，读对应 SPI 的 javadoc——契约都写在接口上。*
