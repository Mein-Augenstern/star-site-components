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

**根因**：bench 的 `other` 客户端独立 provisioner/mapper session，H2 跨 session 可见性
存在时序问题——`other.tryLock` 在 `lock` 内层释放后仍看到旧值

**计划**：排查 H2 MVCC 语义；可能需改为同 session 或引入同步屏障

### BUG-007 [P3] bench A2/A3 H2 MyBatis 构造器不匹配

**发现场景**：进程内 bench

**根因**：MyBatisAsyncCommandStore 加了 TimeSource 参数后 bench 代码仍用旧 4 参构造器

**计划**：bench 代码同步新构造器签名

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

**环境处置**：本地两表已按新 DDL 重建（DROP + 组件自举），workerId 恢复租约分配。

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

**建议**：满批认领后立即进入下一轮（drain 循环），空轮询再回到固定间隔；默认 batchSize
可提至 50。

### OBS-102 [观察] lock timed-wait 100ms 轮询量化

**发现场景**：16 线程争抢下 `lock wait 100ms` P95≈296ms（直连 P95≈105ms）。

**解释**：`LockClient.WAIT_RETRY_INTERVAL_MS=100` 固定轮询，高争抢下每次失败等满
100ms 再试，延迟逼近 `waitMs + k×100ms`。属轮询式等待的固有量化，非正确性问题；
后续可做自适应退避（首次 10ms 起）优化尾延迟。

### OBS-103 [P3] lock mysql 形态隐式依赖 MyBatis 装配

**发现场景**：试图关闭 bench 的 MybatisAutoConfiguration → lock 启动失败
（错误信息清晰：`starsite.lock.mode=mysql 需要 ScheduleLockMapper Bean`）。

**说明**：lock 的 mysql 实现走 MyBatis mapper（async/sequence 走 jdbc）——三组件
mysql 形态实现栈不一致，且 lock starter 未把 mybatis-spring-boot-starter 声明为
传递依赖。fail-fast 消息友好，记为文档/依赖声明改进项（README 标注 + starter 可选依赖）。

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
