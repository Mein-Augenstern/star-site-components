# 分布式存储方案：Redis Cluster / 分库分表 / OceanBase（2026-10-31 立项）

> 状态：<b>方案设计（待评审）</b> · 目标版本 0.3.0 → 0.5.0 分三阶段
> 范围：三组件（sequence / lock / async-command）的存储层分布式化。
> 场景梯度：**10 库 10 表 → 百库百表 → OB 存储原生分布式**。

## 0. 总体原则（先立规矩）

| # | 原则 | 一句话理由 |
|---|---|---|
| P1 | **控制面/数据面分离** | 低频元数据（workerId 租约、分片注册）独立库；高频业务数据分片——控制面单点可接受，数据面必须可扩展 |
| P2 | **路由确定性** | 同一分片键恒定路由到同一物理库表（hash-mod），这是互斥/幂等语义在分片下不降级的根基 |
| P3 | **语义不降级清单** | 分片后必须逐条验证：lock 互斥恰一胜 / async 幂等计数同片闭合 / workerId 全局唯一——任何一条不满足则方案作废 |
| P4 | **组件不重造分片中间件** | 应用侧路由只做「逻辑表→物理坐标」的确定性计算；连接管理用多数据源池；不实现 SQL 解析改写（那是 ShardingSphere 的活），必要时与其集成而非竞争 |
| P5 | **OB 走存储原生路线** | OB 分区表对应用透明——优先「零改动适配 + 认领 SQL 分区裁剪」，不做应用侧分片叠加 |

两条技术路线的选型结论先行：

```
10 库 10 表   → 路线 A：应用侧分片（组件感知路由，ShardingStore 逐分片操作）
百库百表      → 路线 A 增强：分片亲和调度（实例-分片子集绑定，控制面心跳注册）
OB（蚂蚁形态）→ 路线 B：存储原生分区表（组件 SQL 基本不变 + 认领分区裁剪）
Redis        → Redis Cluster 适配（hash tag 聚簇 + 跨 slot 禁令 + 主从 failover 语义声明）
```

## 1. sequence —— 控制面独立 + 号段消热点

### 1.1 痛点分析

- `sys_sequence` 的 `__global__` 计数行是**全局唯一热点行**：所有实例取号都争这一行。
  分库分表解决不了它——workerId 必须全局唯一，**不能按实例哈希分片**（各片独立计数立刻撞号）。
- 雪花 ID 生成本身是纯内存（拿到 workerId 后零存储开销）——扩展性瓶颈只在租约，而租约
  是**低频操作**（每实例进程启动一次）。

### 1.2 方案：租约归控制面，号段可配

```
┌─ 控制面（独立元数据库，不分片）─────────┐   ┌─ 数据面（分片库 ×N）───────┐
│ sys_sequence（__global__ 全局池）        │   │ 业务表（与组件无关）        │
│ shard_heartbeat（0.4.0 分片注册表）      │   │ ...                        │
└──────────────────────────────────────┘   └────────────────────────────┘
        ↑ 每实例启动一次取 workerId                    ↑ 雪花 ID 内存生成，零交互
```

- **配置新增** `lease-datasource: control-plane`：租约走独立数据源（默认回退主库，
  小集群可不拆）。单表单行，承受全部实例的启动取号毫无压力（压测实证：
  bench-service 12 次重启取号零冲突）。
- **号段模式（可选）** `segment-step: N`：`UPDATE ... SET worker_id = LAST_INSERT_ID(worker_id + ?)`
  一次取 N 个（参数绑定，步长 N），实例内号段自增、耗尽再取。用于两个场景：
  a) 业务直接用 sys_sequence 发业务号（高频）——消热点 100 倍；b) OB 上全局行的
  Paxos 组内写热点——同样靠号段摊薄。workerId 租约本身（1024 空间、低频）保持 step=1。
- **Redis 形态天然分布式**：`INCR` 单 key 在 Redis Cluster 自动路由到单 slot，原子性不变；
  Lettuce cluster topology 模式即可，组件零改动（验证项见 §5）。

### 1.3 OB 专项

- OB MySQL 模式兼容 `LAST_INSERT_ID(expr)` 技巧（兼容性验证项）。
- `__global__` 热点行在 OB 上是单 Partition 内单行写——开启号段模式即可摊薄，
  无需 OB 特殊处理。

## 2. lock —— 锁名确定性路由，互斥语义分片不变

### 2.1 痛点分析（分片对锁是最危险的）

- 互斥的根基是「同一 lock_name 恒定锚定**唯一一行**」。分片后若同一锁名被路由到
  不同物理库——两个实例各建一行、各自加锁成功，**互斥静默失效**（比锁慢更糟）。
- 锁库与业务库不同时：锁的加/释放是独立短事务，无两阶段锁风险；但「锁库故障 →
  该锁名业务全停」的爆炸半径要收敛（现有 fail-open 语义已兜底，见 bench-findings OBS-107）。

### 2.2 方案：RouteResolver + 分片内语义零改动

```
lock_name ──hash-mod──▶ (dbIndex, tableIndex) ──▶ 物理坐标 db_{i}.starsite_lock_{j}
```

- **路由规则**：`tableIndex = hash(lock_name) % tablesPerDb`，`dbIndex = hash / tablesPerDb % databases`
  （两层取模保证均匀）。同一锁名**恒定**同坐标（P2）——互斥语义由该分片的
  `uk_lock_name` 唯一键继续保证，`tryAcquire` 原子 UPDATE **一行 SQL 都不用改**。
- **实现形态**：`ShardingDbLockClient` 组合 N 个物理 `DbLockClient`（每库一份
  mapper/连接池），按路由结果委托。互斥/重入/看门狗/运维侧门逻辑全部复用。
- **route-key 选项**：`route-key: lock-name`（默认，锁名空间均匀）或 `route-by: biz`
  （锁路由跟业务分片键走，锁与业务同库——缩小故障爆炸半径 + 同库事务边界，
  代价是锁名分布不均）。文档给出选型表。
- **Redis Cluster 适配**：现实现是「单 key hash + Lua」——Cluster 下单 key 自动落单
  slot，Lua 脚本操作单 key **天然合法**（跨 slot 限制不触发）。需要补的只有：
  a) key 规范加 hash tag `starsite:lock:{name}`——让 watchdog/admin 批量操作（SCAN/MGET）
  可按 slot 聚簇；b) 文档声明主从异步复制下 failover 的锁丢失窗口（AP 语义，
  与 OB/MySQL 形态的 CP 语义对照表）。**不引入 RedLock**（争议大、成本高，
  与现有单实例 Redis 架构不匹配）。

### 2.3 OB 专项

- OB 分区表铁律：**唯一键必须包含分区键**。锁表按 `PARTITION BY HASH(lock_name)`
  分区 + `UNIQUE KEY(lock_name)`（分区键在唯一键内）→ 合法且点查 UPDATE 分区裁剪到单分区。
- DDL 方言包新增 `sql/oceanbase/`（分区表建表语句，SQL 全部外置于现有铁律）。

## 3. async-command —— 三种认领策略按规模晋级（本方案核心）

### 3.1 痛点分析

| 操作 | 分片后的挑战 |
|---|---|
| 提交 INSERT + 幂等检查 countUnfinished | 幂等锚点 (cmd_type, biz_key) 必须与数据**同分片闭合**，跨片检查=幂等失效 |
| 认领 claim（扫 PENDING） | 原单表一条 UPDATE → 分片后必须逐片认领；10 片可轮询，**百片轮询=每 tick 100 次查询不可行** |
| 悬挂回收 reclaimStale | 随认领同片执行（同一张物理表） |
| 管控端点 stats/recent/list | 跨片聚合：求和/归并/分页 |

### 3.2 路线 A：应用侧分片（10 库 10 表 → 百库百表）

**分片键 = biz_key**（与幂等锚点对齐，P3）：

```
biz_key ──hash-mod──▶ (dbIndex, tableIndex)
  提交/幂等/claimById → 单片操作（路由后走现有 JdbcStore/MyBatisStore，SQL 零改动）
  claim/reclaim       → 认领策略（见下）
  admin               → fan-out 聚合
```

**认领策略三级晋级**（`claim-strategy` 配置）：

| 策略 | 机制 | 适用规模 | 成本 |
|---|---|---|---|
| `fan-out`（0.3.0） | tick 内**并行**对全部分片各发一次 claim（ CompletableFuture 扇出，超时独立） | ≤ 16 片（10 库 10 表在内） | 每实例每 tick = 分片数次查询；分片数翻倍查询数翻倍 |
| `shard-affinity`（0.4.0） | 实例-分片**子集绑定**：控制面 `shard_heartbeat` 表（复用 §1 控制面库），实例心跳认领分片子集（一致性哈希分配 + 故障转移），每实例只扫自己的片 | 百库百表 | 每实例每 tick = 子集数次查询；实例数与分片数同增，水平扩展 |
| `external`（已有） | 宿主调度器自行驱动 `tick(shard)` | 任意 | 组件提供分片枚举 API，编排交给外部 |

- fan-out 下认领互斥依旧由每片的原子 UPDATE 保证（片间无协调需求——不同片本就无竞争）。
- shard-affinity 的注册表**不引入 zk/nacos 新依赖**：控制面库一张心跳表
  （instance_id, shard_set, heartbeat_at），复用 sequence 租约的乐观锁套路；
  实例掉线（心跳超时）后其分片子集被存活实例接管——与 async 悬挂回收同构的自愈语义。

**管理端点 fan-out 聚合**（0.4.0）：
- stats：各片计数求和（无状态，简单）。
- recent：各片 top-N → 归并取全局 top-N（K 路归并）。
- list 分页：各片取 offset+size → 归并去重 → 全局排序截断（**浅分页正确**）；
  深分页走游标（`WHERE id < ?` 参数绑定，本来就在待办清单，与分页归并一并交付）。

### 3.3 路线 B：OB 存储原生（蚂蚁形态）

**核心红利：组件 SQL 零改动**——`async_command` 建成分区表即得分片：

```sql
-- OB MySQL 模式（DDL 方言包外置；业务值一律 ? 参数绑定由应用层保证）
CREATE TABLE async_command (
  ... 现有列不变 ...
  PRIMARY KEY (id, biz_key),            -- OB 分区表主键必须含分区键
  UNIQUE KEY uk_type_biz (cmd_type, biz_key),
  KEY idx_status_next (status, next_run_at) LOCAL,
) PARTITION BY HASH(biz_key) PARTITIONS 64;
```

- 提交/幂等（WHERE 带 biz_key）→ **自动分区裁剪**，行为与单表一致。
- **认领是唯一要优化的点**：`claim` 的 UPDATE 不带 biz_key → 无分区键可裁剪 → 全分区扫。
  两个方案二选一（`claim-partition-strategy`）：
  - `round-robin`（推荐默认）：认领 SQL 加 `PARTITION(p?)` 谓词按分区号轮询
    （参数绑定分区号，tick 内轮换起始分区防倾斜）——每次只扫一个分区，
    64 分区 64 个 tick 轮完，PENDING 延迟上限 = 分区数 × pollInterval（可接受，
    且 shard-affinity 思想的存储内版本）；
  - `global-index`：`idx_status_next` 建全局索引——认领走全局索引免全分区扫，
    代价是每次写入的跨分区索引维护（写放大，OB 文档明确权衡）。
- admin 聚合：OB 单表分区对应用透明——stats/recent/list 的 SQL 不变，
  **聚合成本 OB 内部承担**（这是路线 B 相对路线 A 最大的运维红利）。

## 4. 配置模型（三组件统一词汇）

```yaml
starsite:
  sharding:                        # 统一分片规格（三组件共享，P4：只算坐标不管连接）
    enabled: false
    databases: 1                   # 10 → 100
    tables-per-database: 1         # 10 → 100
    algorithm: hash-mod            # 目前唯一实现；预留 range/自定义 SPI
    datasource-refs:               # 库序号 → 数据源 bean 名（多数据源由宿主声明）
      0: shardDs0
      1: shardDs1                  # ...（10/100 个；或 single: shardingDataSource 委托 ShardingSphere）

  sequence:
    mode: mysql
    lease-datasource: controlPlaneDs   # 控制面数据源（缺省回退主库）；redis 形态忽略
    segment-step: 1                    # 号段步长（业务发号/OB 热点场景调大）

  lock:
    mode: mysql                        # sharding 下仍为 mysql——由 starsite.sharding.enabled 驱动
    route-key: lock-name               # or biz（跟业务分片走，同库故障域）

  async-command:
    mode: mysql
    route-key: biz-key                 # 固定为 biz-key（幂等锚点对齐，不开放其他选项）
    claim-strategy: fan-out            # fan-out / shard-affinity / external
    claim-partition-strategy: round-robin   # 仅 OB 路线：round-robin / global-index
```

- **兼容性**：`starsite.sharding.enabled=false`（默认）时行为与 0.2.x 完全一致——
  单库用户零感知。`sharding.enabled=true` 属**新能力**，进 0.3.0（minor 版本，
  无 breaking；`mode` 枚举不动，避免 0.2.0 式配置键迁移的阵痛重演）。
- 路由抽象最小接口（core 层，零框架依赖）：
  `ShardRoute resolve(String routeKey)` / `int shardCount()`——starter 装配
  ShardingStore 时逐物理坐标构建底层 Store 并按路由委托。

## 5. 验收标准（延续 bench 铁律：每个设计决策配压测证明）

| 验收项 | 方法 | 通过线 |
|---|---|---|
| 分片均衡性 | 10 库 10 表灌 100 万命令，按片计数 | 片间偏差 < 10% |
| 分片下互斥恰一胜 | 双实例 × 10 片并发争同名锁 1000 轮 | 0 双持有 |
| 分片下幂等闭合 | 同 (type,bizKey) 并发提交 1000 次 | 每片去重正确，总余量 = 1 |
| workerId 跨库唯一 | 100 实例模拟并发取号（10 库控制面） | 0 撞号 |
| 认领互斥（fan-out） | 双实例 × 10 片 × 万级命令 | 0 重复执行 |
| 分片亲和故障转移 | kill 持片实例，观测分片接管 | 心跳超时内接管，0 命令滞留 |
| 单分片故障隔离 | kill 1/10 库，压测其余分片 | 其余分片吞吐降幅 < 15%，故障片请求显性报错（非静默挂起——JDBC 超时参数铁律） |
| OB 分区裁剪 | EXPLAIN 认领 SQL（PARTITION(p?)） | 扫描分区数 = 1 |
| Redis Cluster | 3 主 3 从 cluster 模式压三组件 | Lua/INCR 全部单 slot 合法；主从切换后语义符合文档声明 |
| 百库百表可运维 | 100 库 fan-out vs shard-affinity 对比 | affinity 模式每 tick 查询数 ≈ 子集数（不随总分片数线性涨） |

bench-service 同步扩展：`SHARDING_ENABLED` 环境形态 + 多数据源装配 + 分片压测场景
（均衡性/故障隔离/亲和转移），复用既有 bench.py 复跑。

## 6. 分阶段交付（对齐版本号）

| 阶段 | 版本 | 内容 |
|---|---|---|
| Phase 1 | 0.3.0 | ShardingSpec/RouteResolver 抽象 · sequence 控制面数据源 + 号段 · lock 确定性路由（ShardingDbLockClient）· async fan-out 认领 · 10 库 10 表全量验收 · Redis Cluster 适配验证 |
| Phase 2 | 0.4.0 | shard-affinity（心跳注册表/接管）· admin fan-out 聚合 + 游标分页 · 百库百表压测 · route-by:biz 选项 |
| Phase 3 | 0.5.0 | OB 方言包（分区表 DDL + round-robin 认领）· OB 实机验收 · Redis hash tag 全量落地 · 分布式部署章节进 README |

## 7. 风险与开放问题（评审重点）

1. **管理端点深分页归并**的正确性边界（归并去重的 offset 语义）——Phase 2 设计时
   用游标分页彻底绕开，不做全局 offset。
2. **shard-affinity 注册表本身是控制面新依赖**——控制面库故障时退化为 fan-out
   （可配 `affinity-fallback: fan-out`），保底可用性。
3. **OB 全局索引写放大**——round-robin 为默认，global-index 只作为压测数据支持下的
   可选项，不默认推荐。
4. **Redis 主从 failover 锁丢失窗口**——文档声明（AP vs CP 对照表）而非代码掩盖；
   强一致场景引导用户用 mysql/OB 形态（fail-open 语义已实证，OBS-107）。
5. **hash-mod 再平衡**——分片数变更（10 库→100 库扩容）会导致路由全变，锁行/命令
   历史不迁移则互斥/幂等失效。扩容路径 = 新旧分片群双写过渡 or 停写窗口迁移，
   Phase 2 出专项设计（一致性哈希只缓解不消除）。
