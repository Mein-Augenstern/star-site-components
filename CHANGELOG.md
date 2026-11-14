# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

五号组件 idempotent 落地 + 全组件扩展口子统一 + 伴随的构建改进（schedule 组件 2026-11 交付一并归入本段）。

### Fixed
- **idempotent 代际令牌时序精度 bug**（demo 实证）：引擎用带纳秒的 LocalDateTime 做键
  窗口边界，MySQL DATETIME 秒精度截断后 complete 的 expire_at 等值条件永不命中——
  终态落不下去（行滞留 PROCESSING，后续同键请求等成 CONFLICT）。修法：窗口边界统一
  归整到秒（windowEnd），代际令牌在 DATETIME/定长 ISO/memory 三形态精度一致
- **sequence 装配循环依赖**（demo 实证）：扩展口拉齐后主发号器急切解析分配器 Bean，
  MyBatis 分配器工厂又急切解析发号器写审计行主键——环。修法：MyBatis 分配器的审计
  行发号改惰性解析（分配时才取，取不到回自举雪花——自举兜底本为此设计）；顺带修正
  存量暗坑：mode=mysql + MyBatis 在场时分配器 Bean 此前建而未用（租约实际走 raw JDBC）
- **demo README 存量坑**：步骤 1 的 docker run 未建 starsite_demo 库（compose 文件才有）
  ——补 MYSQL_DATABASE；demo 升级五组件（新增幂等下单端点与回放演示）

### Added
- **idempotent 组件 v0.1（五号组件）**：API 幂等键，Stripe 同款语义——同一键重复请求
  **恰一执行 + 首次成功结果回放**（不只是拦截）。`@StarsiteIdempotent(key = "#orderId")`
  注解路径（SpEL 键 + Jackson 回放 + 启动期缺 Jackson 快速失败）与 `IdempotentClient.guard`
  门面路径（载荷自管，core 零 JSON 依赖）；uk(scope,idem_key) INSERT 原子认领 +
  条件回收（FAILED/过期/停滞三合一）+ **代际令牌双条件守卫**（complete/fail 须匹配本代
  expire_at——回收是原地重置，仅 status 守卫分不出新旧认领者）；键记忆窗口 24h（Stripe
  同款，过期即键遗忘重执行）、失败释放键重执行、在飞等待（目睹失败返回摘要不自动重跑
  ——防重试风暴）、崩溃遗留惰性自愈；retention 过期看护默认开（瞬态去重状态非业务数据，
  OBS-111 教训前置）；jdbc/mybatis/memory 三 store 等价 + 结构校验 fail-fast + 健康端点
  （FAILED 不判 DOWN——可重试业务自然态，与 async 死信语义区分）。47 项单测 + 真库 IT。
- **idempotent redis 形态（2026-11-13）**：`store=redis`——hash 六字段 + 四段 Lua
  原生原子（脚本外置 `resources/sql/idempotent-*.lua`，与 lock 同形），**TTL 即键记忆
  窗口**（认领 PEXPIRE，过期清理 Redis 原生承担）；时间值定长 ISO 串做 Lua 比较
  （字节序即时间序，免 epoch 换算与时区陷阱）；观测降级——countByStatus 保持缺省 -1 /
  purgeExpired 缺省 0，健康端点对负值省略微报；`redis-key-prefix` 配置（starsite:idem:）；
  显式 store=redis 缺 StringRedisTemplate 启动期快速失败（不静默降级）。
- **第三方存储扩展指南**（docs/idempotent.md §5.5）：三层通道——MySQL 兼容库
  （OceanBase/TBase-MySQL）jdbc 直用、方言差异库实现 `IdempotentSql` Bean 换方言、
  完全异构存储实现 `IdempotentStore` SPI（四原子操作硬契约）注册 Bean 全链路复用。
- **全组件扩展指南**（docs/extension.md，公司内部落地专用）：三层接入通道
  （L1 配置换连 / L2 方言 Bean / L3 SPI 实现 + Bean 让位）+ 五组件扩展点清单
  （SPI/方言/硬契约逐项）+ 内部落地检查清单 + TBase 方言/内部锁服务/平台机器位三示例。
- **sequence 扩展口拉齐**：自定义 `WorkerIdAllocator` Bean 此前仅 mysql+mybatis 路径
  让位——拉齐为**所有形态**（standalone/mysql-jdbc/redis）Bean 优先于内置构建
  （公司内部平台/ZK/StatefulSet 序号发机器位即插即用）。

### Changed
- **存储实现拆包并归位抽象包下**（SNAPSHOT 阶段 breaking，五组件统一）：`store/`
  （sequence 为 `allocator/`）作为独立抽象包只放契约层（Store SPI + 方言接口 + 值对象），
  实现作为其**子包**（开闭原则：契约对修改关闭，新增存储=新增子包零改动）——
  `store.jdbc`/`store.memory`/`store.redis`/`store.mybatis`，lock 另有 `store.db`，
  sequence 为 `allocator.mysql`/`allocator.redis`/`allocator.standalone`/`allocator.mybatis`。
  包名变更影响直接 import 实现类的消费方（走 SPI/门面/Bean 注入的不受影响）；
  Mapper XML 同包发现（D3）随子包迁移同步
- **idempotent SPI 终态操作改自然键寻址**（SNAPSHOT 阶段 breaking）：complete/fail
  签名从 `(long id, expireAt, ...)` 改为 `(scope, idemKey, expireAt, ...)`——表形态
  uk 命中与 Redis 形态存储键同一寻址位，SPI 一套签名服务全部形态（第三方扩展不受
  自增主键假设约束）
- **IdempotentStore 观测方法降级为缺省实现**：countByStatus 缺省 -1（不支持全库计数
  的形态如 redis 不必硬扫）、purgeExpired 缺省 0（自带原生过期的形态无需清理）——
  SPI 扩展方按自家存储能力选择覆写
- **maven-compiler-plugin 开启 `-parameters`**（spring-boot-starter-parent 3.2+ 同款默认）：
  Spring Framework 6.1 移除 LocalVariableTableParameterNameDiscoverer——无该标志参数名不可
  反射，idempotent 组件的 SpEL 键 `#orderId` 依赖之（BPP 另配 `#p0`/`#a0` 位置引用兜底）

## [0.2.3] — 2026-10-31

Loop 压测（8 轮循环 + 环境采样）产出的完整性修复。

### Added
- **retention 终态行清理（OBS-111）**：loop 压测实证 SUCCESS/DEAD 行无清理机制、
  单日堆近 4000 行、表无限累积。新增 `starsite.async-command.retention-enabled /
  retention-days(7) / retention-batch-size(500)`——默认关（不动用户数据的安全默认，
  生产强烈建议开启）；调度器每小时窗口低频触发，两步法批量删除（分片红线合规）+
  满批追赶（单窗口最多 20 轮，真机验证 3451 条积压单窗口 7 轮清光）；
  PENDING/RUNNING 永不清理；JDBC/InMemory/MyBatis 三实现等价。

## [0.2.2] — 2026-10-31

分片形态实装（distributed-sharding.md 路线 A · ShardingSphere Proxy 5.4.1）产出的兼容性演进。

### Changed
- **崩溃回收改两步法**（`UPDATE..LIMIT` → `SELECT id LIMIT` + `IN` 回收）：单条
  UPDATE..LIMIT 不被分片中间件支持（ShardingSphere ERROR 20046——全局 LIMIT 语义在
  多分片路由下不成立）。两步法 SELECT..LIMIT 广播后按片归并（每片各取 limit 条），
  单表形态行为不变。JDBC 与 MyBatis 两路径同步（三形态等价铁律）。
- **方言 XML 路径迁移** `resources/sql/mysql/` → `resources/dialect/mysql/`：
  ShardingSphere Standalone JDBC 仓储按 classpath `sql` 目录枚举并 JAXB 解组 XML
  （期待其自身 `<sql>` 方言脚本），与组件 `<dialect>` 根元素正面冲突。迁移后语义更准
  （方言配置而非 SQL 脚本），DDL `.sql` 与 Lua 不受影响。

### Verified（1 库 × 10 表实机，经 Gateway 生产链路）
- 分片均衡：200 条命令均匀落 10 片（19~21 条/片，偏差 <10%）
- 互斥路由：锁名 hash 散布多片（同键恒定同片，互斥语义与单表等价）
- 租约/LAST_INSERT_ID 过 proxy 正常（workerId 全局唯一分配）
- fan-out 认领广播 + 满批追赶在分片下正常（951 条积压清零，0 错误）
- 吞吐代价：proxy 形态较直连 -20~30%（独立进程网络跳 + SQL 解析改写，符合社区经验）

## [0.2.1] — 2026-10-31

生产链路压测（Gateway → bench-service → MySQL 实机）产出的修复与增强批次。

### Fixed
- **BUG-008**: starter 的 `AsyncCommandAdminController` 类级 `@RestController` 会被宿主
  `scanBasePackages` 宽扫描直接实例化，绕过 `admin-enabled=false` 条件装配导致启动失败
  ——改为类级 `@ResponseBody`（非 stereotype，不被扫描；MVC 识别/JSON 语义不变）
- **BUG-009**: 存储结构漂移静默失败——全部 5 个建表点（lock、async×2 路径、sequence×2 路径）
  增加启动期结构校验：表存在但缺契约列 → fail-fast 并给出迁移指引（旧结构曾导致
  workerId 静默回落 hash 撞号、调度器每 5s bad SQL grammar 且命令永久积压）
- **BUG-006**: bench L3 可重入 0/1000——双层真因：H2Infra 的 mapper session 被
  try-with-resources 关闭（fail-open 掩盖异常）+ 全场景固定种子 id=1 主键冲突致锁行缺失
- **BUG-007**: bench A2/A3 构造器不匹配——已随 4 参便捷构造器解决，本轮 bench 实测确认

### Changed
- **OBS-101**: async 调度器满批追赶——认领满批时连续认领直到清空或达 8 轮上限
  （追赶吞吐 8×batch/interval）；默认 `batch-size` 10 → 50（750 条积压消化从 ~6 分钟
  降至秒级，生产链路压测实证）
- **OBS-102**: lock 限时等待自适应退避——重试间隔 10ms 起步逐次翻倍、封顶 100ms
  （16 线程争抢下 P95 从 ~3×100ms 轮询量化长尾降一个量级；高争抢节奏与旧版一致）
- **OBS-103**: lock 文档标注 mysql 形态的完整依赖清单（mybatis-spring-boot-starter +
  DataSource + sequence starter + mapper-locations）

## [Unreleased]

### Added (0.2.0)
- Lock: watchdog auto-renewal (`WatchdogLockClient` decorator + `starsite.lock.watchdog-enabled=true`)
  — automatically renews lock TTL every TTL/3 for long-running tasks
- Sequence: Redis workerId lease (`RedisWorkerIdAllocator` + `starsite.sequence.mode=redis`)
  — atomic INCR allocation, sub-millisecond, for teams with Redis but no MySQL
- Lock: admin client (forceRelease / findHolder / listHeldLocks / getLockInfo)

### Changed (0.2.0)
- BREAKING: config key unification — `store: jdbc|memory` → `mode: mysql|standalone` (async-command);
  `mode: db` → `mode: mysql` (lock); three components now share unified vocabulary `mode=standalone|mysql|redis`
- JDK target lowered from 21 to 17 (Spring Boot 3 minimum, broader compatibility)
- sequence starter changed from `provided` to `compile` scope (single-component users no longer hit missing StarsiteIdGenerator)

### Fixed (0.2.0)
- MyBatisMysqlWorkerIdAllocator: retry null → throw exception (was silently defaulting workerId=1)
- MyBatisAsyncCommandStore.reclaimStale: hardcoded clock → injected TimeSource (parity with JdbcStore)
- DemoController: Map.of null name → NPE (normalized upfront)
- Multiple stale javadoc fixes (.properties→.xml, GeneratedKeyHolder residue, dangling @link)

### Added
- Sequence: snowflake ID generator with clock-backward clamping (CLAMP/FAIL)
- Sequence: MySQL workerId lease (`__global__` pool + `LAST_INSERT_ID` atomic increment)
- Async-command: token-claim scheduling with exponential backoff retry + dead letter
- Async-command: three execution modes (SCHEDULED / ASYNC_POOL / IMMEDIATE)
- Async-command: batch executor support
- Async-command: distributed scheduling modes (COMPETING / LEADER / EXTERNAL)
- Async-command: Micrometer metrics + health indicator + admin endpoints
- Lock: reentrant distributed lock (DB `hold_count` column / Redis hash)
- Lock: timed wait acquisition (`tryLock(name, waitMillis)`)
- Lock: row-loss self-healing
- Lock: admin client (force release / list held locks / diagnostics)
- All: MyBatis adapter (Mapper XML auto-discovery via same-package convention)
- All: SQL externalization (Mapper XML + dialect XML + Lua files)
- All: index completeness unit test assertions
- All: list/detail column separation (payload TEXT excluded from list queries)

### Changed
- Lock table renamed from `component_schedule_lock` to `starsite_lock`
- README restructured: 87-line overview + detailed docs in `docs/`

## [0.1.0] - TBD
