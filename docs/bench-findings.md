# 压测发现记录（Completeness Record）

> [← 返回概览](../README.md)

本文档记录压测过程中发现的所有问题——已修复的标注修复方案，
未修复的标注根因与计划，作为组件完整性的可追溯记录。

## 已修复

### BUG-001 [P1] Lock/Sequence starter 的 Redis 类型签名硬依赖

**发现场景**：RPC 压测——demo-spring-boot 以 `mode=mysql` 启动，无 Redis 依赖，
启动即崩：`TypeNotPresentException: StringRedisTemplate not present`。

**根因**：`LockAutoConfiguration.lockClient()` 与 `StarsiteSequenceAutoConfiguration.starsiteIdGenerator()`
的方法签名引用 `ObjectProvider<StringRedisTemplate>`。Spring 解析 @Bean 方法签名时急切加载参数类型，
Redis 类不在 classpath 即抛异常——即使 `mode=mysql` 根本不走 Redis 分支。

**修复方案**（`19f457b`）：
- Lock：主 `lockClient()` 加 `@ConditionalOnProperty(havingValue = "mysql")`，不引用 Redis 类型；
  Redis 分支移入嵌套 `RedisModeConfig`（`@ConditionalOnClass(StringRedisTemplate.class)` 守卫）
- Sequence：同构处理——主 Bean 排除 Redis，嵌套 `SequenceRedisConfig` 接管
- **原则沉淀**：auto-configuration 的主配置类方法签名不引用可选依赖的类型；
  可选形态一律走嵌套 `@ConditionalOnClass` 配置类（Spring Boot 官方同款模式）

### BUG-002 [P2] MyBatisMysqlWorkerIdAllocator 回绕重取号静默默认 workerId=1

**发现场景**：七尺巡检（2026-10-31 第二轮）

**根因**：回绕重取号返回 null 时 `workerId = reTaken == null ? 1 : reTaken`——与 standalone
默认 1 撞号风险。JDBC 版同场景抛 SQLException。

**修复**：null → 抛 `IllegalStateException`（对齐 JDBC 版语义）。

### BUG-003 [P2] MyBatisAsyncCommandStore.reclaimStale 硬编码系统时钟

**发现场景**：七尺巡检——8de4ac8 给 JdbcStore 注入 TimeSource，MyBatis 形态漏改

**修复**：注入 TimeSource + 兼容构造重载，与 JdbcStore 同源可测。

### BUG-004 [P2] docs 配置键残留旧值（store: jdbc/memory, mode: db）

**发现场景**：七尺巡检——0.2.0 配置统一后 docs/README 双语版未同步

**根因**：`store: memory` 为非法枚举值，Spring 静默忽略后落到默认 `mysql`——想要内存队列的
用户反向踩坑（要 DB 的碰巧对，要内存的直接连库）。

**修复**：全部改为 `mode: mysql/standalone`。

## 待修复

### BUG-005 [P2] Lock 嵌套 Redis 配置类的 Environment→ApplicationContext 强转

**发现场景**：RPC 压测 demo 启动失败（第一次修复尝试时引入）

**根因**：`StandardEnvironment` 不是 `ApplicationContext`，cast 必败。

**修复**：直接注入 `StringRedisTemplate` 参数（嵌套类已被 @ConditionalOnClass 守卫，签名安全）。

**状态**：✅ 已修复（随 BUG-001 一并解决）

### BUG-006 [P3] bench L3 可重入测试 0/1000 通过

**发现场景**：进程内 bench

**根因**（0.2.1 修复时查明，双层真因，与最初猜想的 H2 MVCC 无关）：
1. `H2Infra.lockMapper` 的 try-with-resources 立即关闭 SqlSession——mapper 代理每次调用
   抛 "Executor was closed"，而 DbLockClient 的 fail-open 把存储异常吞掉直接放行
   （`other.tryLock` 永远返回 true → blockedPhase 全对/acquirePhase 全错的假象）
2. 全场景共用一张 H2 表但种子行主键固定 `() -> 1L`——首场景占掉主键 1，后续种子
   INSERT 主键冲突被 provisioner 静默吞掉，**锁行从未存在**，tryLock 全部空转

**修复**：mapper session 保持打开（由 mapper 生命周期持有）；种子 id 改类级 AtomicLong
递增。L3 从 0/1000 → **1000/1000**。方法论沉淀：相位计数诊断（blockedPhase/acquirePhase）
+ 行终态 DIAG 是定位"语义全错但吞吐正常"类问题的标准手段。

**状态**：✅ 已修复（0.2.1）

### BUG-007 [P3] bench A2/A3 H2 MyBatis 构造器不匹配

**发现场景**：进程内 bench

**根因**：MyBatisAsyncCommandStore 加了 TimeSource 参数后 bench 代码仍用旧 4 参构造器

**修复**：组件侧已补 4 参便捷构造器（委托系统时钟），0.2.1 轮 bench 实测 A2/A3 全过确认。

**状态**：✅ 已修复（确认于 0.2.1）

## 生产链路压测发现（bench-service + Gateway 实机，2026-10-31）

### BUG-008 [P1] AsyncCommandAdminController 类级 @RestController 被宽扫描绕过条件装配

**发现场景**：star-site-backend 约定 `scanBasePackages = "com.xp.starsite"`（全家桶既定写法），
bench-service 引 async-command starter 后启动失败：Controller 被组件扫描直接实例化，
构造参数 `AsyncCommandAdminService` 因 `admin-enabled` 默认 false 未装配 → NoSuchBean。

**根因**：starter 中经 `@Bean` + `@ConditionalOnProperty` 条件注册的类，自身又带
`@RestController`（@Component 派生注解）——宿主宽扫描时绕过全部条件装配，条件开关形同虚设。

**修复**：类级 `@RestController` → `@ResponseBody`（非 stereotype，不被扫描）；MVC 识别
沿用类级 `@RequestMapping`，`@ResponseBody` 保持 JSON 语义。全组件巡检：这是唯一一个
带 stereotype 注解的 starter 类（core 模块 Config/Properties 类均为纯载体）。

**状态**：✅ 已修复（本轮压测直接产出）

### BUG-009 [P1] 存储结构漂移静默失败（表存在但结构陈旧）

**发现场景**：本地 MySQL volume 由旧版 DDL 初始化，组件升级后两处中招：

- `sys_sequence` 旧表无 `id` 列 → MysqlWorkerIdAllocator 取号 SQL 报
  `Unknown column 'id'` → **静默回落 hash workerId**（恰是取号器要根治的跨服务撞号场景）
- `async_command` 旧表用 `created_at/updated_at`（新标准 `gmt_*`）→ 调度器每 5s 报
  bad SQL grammar，命令永久积压，启动却"成功"

**根因**：`CREATE TABLE IF NOT EXISTS` 只认表名不认结构；结构漂移在运行期以零散 SQL
错误暴露，无启动期 fail-fast。

**计划**：provisioner 建表后增加轻量结构校验（DESCRIBE 比对关键列），缺列即启动失败并
打印迁移提示；hash 回落路径降级为 ERROR 级日志 + 指标暴露。

**修复（0.2.1）**：全部 5 个建表点已实现结构校验 fail-fast——JDBC 路径走
`DatabaseMetaData.getColumns`（零 SQL、天然参数化），MyBatis 路径走 mapper
`#{}` 绑定的 INFORMATION_SCHEMA 查询；表不存在/查询失败仍只告警不阻断（DBA 管理场景）。
新增 3 个漂移回归用例（lock H2 + sequence/async 真库 IT）。backend 的 hash 回落日志
同步降级为 ERROR 并附排查动作。

**环境处置**：本地两表已按新 DDL 重建（DROP + 组件自举），workerId 恢复租约分配。

**状态**：✅ 已修复（0.2.1）

### BUG-010 [P2] 组件抽取后 star-site-backend Docker 全新构建断链

**发现场景**：重建 Gateway 镜像（`docker compose build`）失败——`star-site-common`
依赖 `com.xp.starsite:starsite-*:0.2.0`，该坐标仅存在于本机 ~/.m2，任何远程仓库
（aliyun/central）均无 → 容器内整仓构建 resolve 失败。当前在跑镜像全是组件抽取前构建的。

**根因**：组件未发布到任何可拉取的仓库；backend 的 Dockerfile 整仓自构建假定依赖可达。

**计划**：组件发布 Maven Central（或 GitHub Packages + settings 注入）。本轮临时绕过：
buildx `--build-context m2repo=~/.m2/repository` 注入组件产物（临时 Dockerfile，未进仓库）。

### OBS-101 [P3] async 调度器满批认领后无追赶轮询

**发现场景**：压测注入 750 条命令，积压消化仅 ~2 条/秒（默认 `pollIntervalMs=5000` ×
`batchSize=10`），6 分钟才能清完。

**修复（0.2.1）**：tick 改满批追赶——连续认领直到不满批或达 `MAX_DRAIN_ROUNDS=8` 上限
（池拒绝按失败退避不丢命令，上限防打爆队列）；默认 `batchSize` 10 → 50。新增 2 个
回归用例（单 tick 连续清积压 / 轮数上限有界）。追赶吞吐 = 8×50/5s = **80 条/秒**（旧 2 条/秒）。

**状态**：✅ 已修复（0.2.1）

### OBS-102 [观察] lock timed-wait 100ms 轮询量化

**发现场景**：16 线程争抢下 `lock wait 100ms` P95≈296ms（直连 P95≈105ms）。

**修复（0.2.1）**：重试间隔改自适应退避——`WAIT_BACKOFF_BASE_MS=10` 起步逐次翻倍、
封顶 `WAIT_RETRY_INTERVAL_MS=100`。短等待首次重试快 10 倍，高争抢退回 100ms 节奏
（负载不升高）。新增 3 个确定性用例（短等待提速 / 封顶不过载 / 窗口耗尽语义）。

**状态**：✅ 已修复（0.2.1）

### OBS-103 [P3] lock mysql 形态隐式依赖 MyBatis 装配

**发现场景**：试图关闭 bench 的 MybatisAutoConfiguration → lock 启动失败
（错误信息清晰：`starsite.lock.mode=mysql 需要 ScheduleLockMapper Bean`）。

**处置（0.2.1）**：docs/lock.md 标注 mysql 形态完整依赖清单（mybatis-spring-boot-starter +
DataSource + sequence starter + mapper-locations）；fail-fast 消息本就含修复指引，保留。

### OBS-104 [P3] Hikari 默认 10 连接是 128 并发的第一个排队点（但不是吞吐瓶颈）

**发现场景**：极限摸高——async 提交 128 线程时 `hikaricp.connections.active=10/10`、
`pending=112`（actuator 实时采样），吞吐平台 ~390 ops/s、P99 769ms、零错误。

**A/B 归因**：池 10→20 后 P99 769→463ms 但吞吐不涨（345→333）——排队缓解、吞吐天花板
在 MySQL 单机写入（同机 Docker 卷 fsync）。**加池≠加吞吐**，生产应按
「实例数 × 池上限 ≤ MySQL max_connections」配池并明确诉求是降尾延还是提吞吐。

**处置**：bench-service yml 显式 `maximum-pool-size=20` 并注释容量公式（本仓库）；组件侧
无代码改动（连接池归属宿主装配，组件不越界）。

### OBS-105 [观察] 三组件超限形态均为「优雅排队」而非「错误崩溃」

**发现场景**：摸高至各路径饱和后仍翻倍（ID 256 线程 / async 128 / lock 128）+ 10000 条洪峰。

**结论**：全路径 0 5xx、0 超时、0 数据不一致、容器 0 重启 0 OOM；超限表现为延迟线性上涨
（排队论形态，lock 的 P50=并发/吞吐精确吻合）。两个硬上限均属设计本质而非缺陷：
lock 互斥 ~110 ops/s（存储点查 UPDATE 串行）；async 消化 80/s/实例（追赶上限公式实测吻合，
横向加实例线性扩展）。容量速查表见 full-benchmark.md「极限摸高压测」章。

## 故障注入发现（2026-10-31，单机 + 双实例小集群）

### OBS-106 [P1] DB 故障的失效形态是静默挂起（JDBC 超时未配是根因）

**发现场景**：docker pause/stop MySQL 注入。stop 后 docker-proxy 仍接受 TCP 但不转发，
MySQL 驱动默认 `connectTimeout=0`（无限）→ 请求**无限挂起**（45s/75s 无响应、无 5xx、
无连接拒绝）；pause 形态 TCP 不断 → 执行中语句无限等（40s 慢 SQL 洪峰恢复瞬间统一完成）。
Tomcat 线程被慢性占用，actuator/health 挂死（编排器将判 Pod 不健康连环重启）。

**修复（bench-service 已落地验证）**：JDBC URL 追加 `connectTimeout=5000&socketTimeout=30000`
→ health 精确 30.0s 返回 500、业务端点 60s 有界显性报错。**生产部署清单必含此二参数**；
组件侧 README 部署段应显式提示（待办）。

### OBS-107 [P2] fail-open 防报错不防挂起；恢复瞬间存在互斥击穿窗口

**发现场景**：DB 故障期间 lock 的 fail-open（争锁异常→放行）不触发（挂起不抛异常）；
**恢复瞬间**挂起连接集中报错 → fail-open **并发放行**（实测同锁名 16 个请求同刻放行）。
多实例语义下 = 所有等待实例同时进入临界区（分布式实证：双实例同形态，DB 恢复瞬间双方
均可能获"锁"）。

**建议**：lock.md 已声明 fail-open 语义；生产建议补两点——业务侧幂等兜底 + 可考虑
`fail-fast` 可选开关（争锁异常时返回 false 拒绝而非放行，牺牲可用性保互斥）。

### OBS-108 [P2] actuator/health 挂死是编排级联故障的引信

**发现场景**：DB stop + 200 线程风暴期间：health 000 挂死（DataSource 指标挂起），而纯内存
端点 /api/bench/id 仍 200/9ms——部分降级而非全站崩。

**建议**：K8s 部署时 readiness/liveness 探针不要用聚合 health（DB 抖动会连环重启全部实例）；
用 liveness=进程级、readiness=分组 health（management.endpoint.health.group）分离。

## 分片形态实装发现（2026-10-31，ShardingSphere Proxy 5.4.1 · 1库×10表）

### OBS-109 [P1] 嵌入式 ShardingSphere-JDBC 与 Spring Boot 3.2 同 classpath 三重冲突

**发现场景**：分片实装先走嵌入式 JDBC（5.5.3 → 5.4.1 两轮），依次撞上：
① 5.5.3 SPI 拆包空洞（URLLoader/Hikari 属性同义词未随发布 jar 提供，StorageUnit NPE）；
② 5.4.1 snakeyaml 1.x vs Boot 3.2 的 2.x（Representer 构造器 NoSuchMethodError，不可调和）；
③ javax JAXB 缺失（JDK 11+ 移除）+ Standalone JDBC 仓储扫描 classpath `sql` 目录 XML
并 JAXB 解组（与组件方言 XML 根元素 `<dialect>` 冲突）。

**处置**：转 **Proxy 独立进程形态**——应用视其为普通 MySQL，三组件依赖与 SQL 零改动，
与 Boot 版本彻底解耦（组件仅保留 0.2.2 的 reclaim 两步法兼容性演进）。代价：吞吐
-15~30%（proxy 跳 + SQL 解析改写）。**教训入册：集成重量级中间件优先进程边界，
classpath 混部是版本地狱的入口**。

### OBS-110 [P2] UPDATE..LIMIT 是分片中间件的兼容性红线（组件已演进两步法）

**发现场景**：分片下调度器每 tick 报 bad SQL grammar——ShardingSphere ERROR 20046：
`UPDATE ... LIMIT can not support route to multiple data nodes`（全局 LIMIT 语义在
多分片路由下不成立，每片 LIMIT n 还是全局 n 无法定义）。

**处置（0.2.2）**：崩溃回收改两步法（SELECT id LIMIT → IN 回收）：SELECT..LIMIT 广播
后按片归并（每片各取 limit 条，上限=limit×片数，语义无害）；IN 回收按 id 等值命中
各自分片；单表形态行为不变。JDBC/MyBatis 双路径同步。

**红线清单（组件 SQL 审计新增门禁项）**：分片友好 SQL 约束——
① 不用 UPDATE/DELETE..LIMIT；② 批量操作用显式 id 集（IN 全参数绑定）；
③ 幂等/点查必须带分片键（lock_name/biz_key）以精确路由；④ 不带分片键的语句
（认领/回收/聚合）接受广播语义并评估放大系数。

## Loop 压测发现（2026-10-31，8 轮循环 + 每轮环境采样）

### OBS-111 [P2] async 终态行无清理策略——表无限累积（0.2.3 已修复）

**发现场景**：8 轮循环压测全绿（吞吐稳定/池值恒定/0 错误），但精确计数暴露
**3952 行中 3951 行是 SUCCESS 终态**——单日压测即堆近 4000 行，无任何清理机制。
长期运行的后果：表膨胀 → 认领索引 IO 放大、磁盘/备份增长。

**修复（0.2.3）**：retention 特性——`retention-enabled`（默认关，不动用户数据的
安全默认）/`retention-days`（默认 7）/`retention-batch-size`（默认 500）；
调度器每小时窗口低频触发，两步法批量删除（SELECT 过期终态 id LIMIT → IN DELETE，
分片红线合规）+ 满批追赶（单窗口最多 20 轮，对齐 OBS-101 语义——首版每小时只清
一批 500 的缺陷在真机验证中暴露并即时修复：3451 条积压从"需 7 小时"变"单窗口 7 轮清光"）。
PENDING/RUNNING 永不清理；三存储实现等价（JDBC/InMemory/MyBatis）；语义测试 2 用例。

**其他健康项（无问题）**：8 轮吞吐无退化、workerId 全局池恒定（无泄漏型增长）、
锁表行数稳定（锁行=互斥锚点设计保留，非堆积）、0 错误 0 容器重启。

## 组件缺陷模式总结（回写 component-builder/reviewer）

| 模式 | 实战例 |
|---|---|
| **可选依赖签名陷阱** | ObjectProvider<T> 签名在 T 不在 classpath 时 Spring 抛 TypeNotPresentException |
| **配置键迁移收尾** | 改键名/枚举后 grep docs/README/starter javadoc/examples 四处旧值 |
| **三形态等价漏改** | 给某一形态加功能后 grep 同义其他形态是否同步 |
| **Map.of 空安全** | REST 端点 Map.of() 传 null 值 → NPE |
| **重取号静默默认** | 分布式取号失败不可返回默认值（撞号风险） |
| **starter 类禁 stereotype** | @Bean 条件注册的类带 @RestController 会被宽扫描绕过条件装配（BUG-008） |
| **schema 漂移检测** | IF NOT EXISTS 只认表名；升级场景须校验关键列并 fail-fast（BUG-009） |
| **私有坐标发布闭环** | 组件被业务仓库依赖后，未发仓即断链其 Docker/CI 全新构建（BUG-010） |
