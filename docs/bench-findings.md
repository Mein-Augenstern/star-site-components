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

## 组件缺陷模式总结（回写 component-builder/reviewer）

| 模式 | 实战例 |
|---|---|
| **可选依赖签名陷阱** | ObjectProvider<T> 签名在 T 不在 classpath 时 Spring 抛 TypeNotPresentException |
| **配置键迁移收尾** | 改键名/枚举后 grep docs/README/starter javadoc/examples 四处旧值 |
| **三形态等价漏改** | 给某一形态加功能后 grep 同义其他形态是否同步 |
| **Map.of 空安全** | REST 端点 Map.of() 传 null 值 → NPE |
| **重取号静默默认** | 分布式取号失败不可返回默认值（撞号风险） |
