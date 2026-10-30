# star-site-components

[![CI](https://github.com/Mein-Augenstern/star-site-components/actions/workflows/ci.yml/badge.svg)](https://github.com/Mein-Augenstern/star-site-components/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

star-site 系列通用组件库：**core（纯库 + MyBatis 可选路径）+ spring-boot-starter（装配）**的开源标准结构，
三个组件（sequence / async-command / lock）均同时考虑**单机与分布式**两种运行形态。
引 starter + 配置即用；core 可脱离 Spring Boot 独立引用。

```
starsite-sequence ──────► starsite-sequence-spring-boot-starter
  雪花序列核心库              自动装配 + MyBatis 租约装配 + 健康端点
  （raw JDBC + MyBatis 双租约路径，Mapper XML 在 core）
starsite-async-command ─► starsite-async-command-spring-boot-starter
  异步命令核心库（分包）       自动装配 + MyBatis 存储装配 + SmartLifecycle + 健康/管控端点
  （jdbc/mybatis/memory 三存储，Mapper XML 在 core）
starsite-lock ──────────► starsite-lock-spring-boot-starter
  分布式锁核心库              自动装配（db/redis 双模式）+ MyBatis 扫描装配
  （Mapper XML 在 core）
```

| 组件 | 单机形态 | 分布式形态 |
|---|---|---|
| **sequence**（雪花序列） | 固定机器位，零外部依赖 | MySQL sequence 表 `__global__` 池原子租约 |
| **async-command**（异步命令） | memory 进程队列（无 DB） | MySQL 表令牌认领（多实例并发互斥） |
| **lock**（分布式锁） | —（锁天然面向多实例） | DB 唯一键互斥 / Redis SETNX+TTL |

- JDK 21 · Spring Boot 3.2.x · **core 零 Lombok**（sequence-core 仅依赖 slf4j + 可选 MyBatis 路径；async-core 仅多 spring-jdbc；MyBatis 为 provided——由消费方 classpath 提供，不传递强制依赖）
- 设计对齐生产实践复盘：[分布式 ID：从雪花算法到生产实现](https://github.com/Mein-Augenstern/MUYI/blob/master/C-%E6%9E%B6%E6%9E%84/%E5%88%86%E5%B8%83%E5%BC%8F%E7%B3%BB%E7%BB%9F/%E5%88%86%E5%B8%83%E5%BC%8FID/%E5%88%86%E5%B8%83%E5%BC%8FID-%E4%BB%8E%E9%9B%AA%E8%8A%B1%E7%AE%97%E6%B3%95%E5%88%B0%E7%94%9F%E4%BA%A7%E5%AE%9E%E7%8E%B0.md)（star-site-backend 实战回写）
- 已在 star-site-backend 生产接入（发号 + AI 解读异步命令 + workerId 租约）

## 组件表规范（三组件统一铁律）

所有组件表（`sys_sequence` / `async_command` / `starsite_lock`）遵守同一套建表标准：

- **物理主键 `id` 由 sequence 组件发号**（insert 前预生成显式写入，**禁 AUTO_INCREMENT**；`sys_sequence` 的 `__global__` 种子行固定 `id=1` 为自举保留）
- **标准时间列 `gmt_create` / `gmt_modified`**（DEFAULT CURRENT_TIMESTAMP，modified 带 ON UPDATE）
- **SQL 配置外置铁律**：MyBatis 消费走 Mapper XML（同包放置，`addMapper` 自动发现）；JDBC 方言走 `resources/sql/mysql/<组件>-mysql.properties`（key=接口方法名）；Redis Lua 走 `resources/sql/*.lua`——Java 代码零 SQL 字符串（含过滤/排序片段）；`${tableName}`/`{table}` 为白名单校验后的表名替换，业务值一律参数绑定
- 高频路径全索引命中（如 `async_command` 的认领 `(status, next_run_at)`、幂等 `(cmd_type, biz_key)`、认领取回 `(claim_token)`）
- DBA 脚本在各组件 `src/main/resources/sql/mysql/`，与代码内 DDL 完全一致（时间列/主键形态勿改）

## 设计说明（模式与取舍）

| 模式 | 落点 | 解决什么 |
|---|---|---|
| 策略 | `WorkerIdAllocator`（Fixed/Mysql）、`AsyncCommandStore`（Jdbc/InMemory/MyBatis）、`LockClient`（Db/Redis） | 单机/分布式双形态一套 API，`mode`/`store` 一行配置切换 |
| 注册表 | `ExecutorRegistry` | 执行器按 `type()` 注册路由，重复 type **启动期**拒绝（不带病上线） |
| 门面 | `StarsiteIdGenerator` / `AsyncCommandClient` / `LockClient` | 业务只面对一个入口，内部结构可演化 |
| 适配器 | starter 的 `AsyncCommandLifecycle`（SmartLifecycle） | core 零 Spring 依赖；容器最后启动/最先停止，不丢尾批任务 |
| 乐观并发 | DB 令牌认领（`UPDATE ... claim_token`） | MySQL 无 `UPDATE..RETURNING` 的经典互斥解，多实例谁写入算谁 |
| 时间注入 | `TimeSource` 可插拔 | 时钟回拨/延迟场景测试确定性构造，测试钩子零侵入主代码 |
| id 解耦 | core 以 `LongSupplier` 声明 id 来源 | 组件间零编译依赖；starter 从容器取 `StarsiteIdGenerator`，缺失快速失败 |

其他开源约定：`@ConditionalOnMissingBean` 全量让位（应用自定义 Bean 优先）、
`@ConditionalOnClass` 按 classpath 装配（无 actuator 不报错）、`spring-configuration-metadata` 配置提示、
健康检查**被动上报不消耗号段**、测试全部位于 `src/test`（正式代码零测试分支）。

## 快速开始

```xml
<!-- 发号（其余两组件的表主键来源） -->
<dependency>
    <groupId>com.xp.starsite</groupId>
    <artifactId>starsite-sequence-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.xp.starsite</groupId>
    <artifactId>starsite-async-command-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.xp.starsite</groupId>
    <artifactId>starsite-lock-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

无需 `@Import`、无需 `@EnableScheduling`——自动装配完成一切（自动建表 IF NOT EXISTS 也可关闭交由迁移脚本）。
非 Spring Boot 项目可直接引 core 模块手工组装（`new SnowflakeIdGenerator(...)` / `new AsyncCommandScheduler(...)`）。
**注意**：async-command 的 `store=jdbc` 与 lock 的 `mode=db` 需要容器中有 `StarsiteIdGenerator`
（即引入 sequence starter），缺失时启动快速失败——组件表物理主键无来源不可降级。

消费方 classpath 有 MyBatis（引了 mybatis-spring-boot-starter，容器中存在 `SqlSessionFactory` Bean）时**零配置自动切换**：
命令存储换 MyBatis 版、workerId 租约换 MyBatis 版、锁表 Mapper 自动扫描注册——SQL 走与接口同包的
Mapper XML，命令表/租约表对消费方的拦截器、分页插件、SQL 日志原生生效；无 MyBatis 的消费方零感知
（自动装配不命中，回退 raw JDBC / 显式提示）。

## ① sequence：雪花 ID

### 单机（默认）

```yaml
starsite:
  sequence:
    mode: standalone   # 默认
    worker-id: 1       # 多实例部署时各不相同即可
```

### 分布式（MySQL 原子租约）

```yaml
starsite:
  sequence:
    mode: mysql
    service-name: ${spring.application.name}   # 审计行 key
    table-name: sys_sequence                   # 随组件自动建表
```

多实例启动时 workerId 从全局池计数行（`__global__`）经 `LAST_INSERT_ID(worker_id + 1)` 原子自增取出，
全平台绝不重复；每个服务实例的占用记录另落审计行（upsert 锚 `uk_service_name` 幂等）。
分配值超机器位上限时按 `wraparound-policy` 处理（默认 RESET 重置并告警，可选 FAIL 拒绝启动）。

### 使用

```java
@RequiredArgsConstructor
class OrderService {
    private final StarsiteIdGenerator idGenerator;   // 注入即用

    void createOrder() {
        order.setId(idGenerator.next());             // 或 nextStr() 字符串形态
    }
}
```

### 时钟回拨（雪花系命门）

回拨发生时把虚拟时间冻结在上次发号毫秒，**借序列位余量继续发号**，等物理时钟追平——id 时间戳字段绝不倒退：

- `backward-policy: CLAMP`（默认）：任何幅度钳制（大回拨告警一次）
- `backward-policy: FAIL`：回拨超过 `max-backward-millis`（默认 2000ms）抛 `ClockBackwardException` 拒绝发号，宁可短暂不可用绝不发倒退 id

### 全量配置（前缀 starsite.sequence）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关 |
| `mode` | standalone | standalone / mysql |
| `worker-id` | 1 | standalone 固定机器位 |
| `service-name` | unnamed-service | mysql 审计行 key |
| `table-name` | sys_sequence | mysql sequence 表 |
| `init-ddl` | true | 自动建表（IF NOT EXISTS） |
| `epoch` | 2026-01-01 | 起始时间戳 |
| `worker-id-bits` | 10 | 机器位宽（1024 实例） |
| `sequence-bits` | 12 | 序列位宽（单毫秒 4096） |
| `max-backward-millis` | 2000 | 时钟回拨容忍上限 |
| `backward-policy` | CLAMP | CLAMP / FAIL |
| `wraparound-policy` | RESET | RESET / FAIL（mysql） |

## ② async-command：异步命令

提交方在业务事务内 `submit`（命令与业务变更同事务原子落库，`AsyncCommandTxTemplate` 回调式编程事务），
调度器认领执行、指数退避重试、超限死信，崩溃实例的悬挂命令自动回收。

### 有 DB 的服务（单机或分布式通用）

```yaml
starsite:
  async-command:
    store: jdbc          # 默认；表自动创建（async_command，可配表名）；需 sequence starter 发主键
```

多实例部署时各实例独立令牌认领（`UPDATE ... claim_token` 互斥），同一命令只会被一个实例执行。

### 无 DB 的单机服务/工具

```yaml
starsite:
  async-command:
    store: memory        # 进程内存队列（重启丢命令，可靠性链路用 jdbc）
```

### 使用

```java
// ① 业务方提交（注入 AsyncCommandClient）
asyncCommandClient.submit("report-generate", "report-123", "{\"format\":\"pdf\"}");
asyncCommandClient.submitIdempotent("bill-notify", "bill-456", "{}"); // 未完成期间防重复
asyncCommandClient.submitAt("order-cancel", "order-789", "{}",
        LocalDateTime.now().plusMinutes(30));                          // 延迟队列

// ② 执行器实现为 Spring Bean（type 即路由键）
@Component
class ReportGenerateExecutor implements AsyncCommandExecutor {
    @Override public String type() { return "report-generate"; }
    @Override public void execute(AsyncCommand cmd) {
        // 正常返回 = 成功；抛异常 = 指数退避重试（60s·2^n 封顶 1h），超 max-retry 进 DEAD
    }
}
```

### 执行模式（三选一，执行器声明）

```java
@Component
class NotifyExecutor implements AsyncCommandExecutor {
    @Override public String type() { return "notify"; }
    @Override public ExecutionMode mode() { return ExecutionMode.ASYNC_POOL; } // 默认 SCHEDULED
    @Override public void execute(AsyncCommand cmd) { /* ... */ }
}
```

| 模式 | 语义 | 适用 |
|---|---|---|
| `SCHEDULED`（默认） | 落库 → 调度线程认领 → **内联执行**（同轮串行） | 轻快任务，顺序敏感 |
| `ASYNC_POOL` | 落库 → 调度只认领分发 → **转交线程池**执行 | 重/慢任务，不阻塞调度与其他类型 |
| `IMMEDIATE` | 落库 → 提交线程立即按 id 认领 → **直投线程池**，不等轮询（默认 5s） | 时效敏感任务；投递前崩溃由调度兜底，可靠性不降级 |

**线程池隔离**（不同任务类型互不争抢）：执行器实现 `PoolIsolatedExecutor.pool()` 返回自持线程池
（bean 注入或自建，应用管理生命周期），该类型命令跑在自己的池；未隔离的池化命令用组件共享池
（`shared-pool-core-size/max-size/queue-capacity` 可配，队列满 CallerRuns 反压不丢任务）。
三种模式失败语义一致：指数退避重试，超限进 DEAD。

### 批量执行器（攒批更高效的场景）

```java
// 实现批量接口：同轮认领到的同类型命令合并成一次调用（组内 id 升序）
// 原子语义：executeBatch 抛异常 = 整批重试（副作用需幂等）；正常返回 = 整批成功
@Component
class PushBatchExecutor implements AsyncCommandBatchExecutor {
    @Override public String type() { return "push-notify"; }
    @Override public void executeBatch(List<AsyncCommand> cmds) {
        // 一次外部调用推完本批（计费接口按次计费/批量落库场景收益最大）
    }
}
```

### 分布式调度模式（schedule-mode）

| 模式 | 语义 | 适用 |
|---|---|---|
| `COMPETING`（默认） | 各实例独立轮询令牌认领，天然分摊 | 通用；命令执行幂等即可 |
| `LEADER` | 经 lock 组件选主，**只有 leader 调度**（注入 `LockClient` Bean 即自动使用） | 命令必须单点顺序消费（对账/汇总类） |
| `EXTERNAL` | 组件不自跑调度，由外部触发器调 `/tick` 端点 | XXL-Job / K8s CronJob 统一编排的场景 |

### 指标（引 actuator/micrometer 即自动生效）

- Counter `starsite.async.commands{status=submitted|success|retry|dead, cmdType=…}`——命令生命周期四态计数
- Timer `starsite.async.execution{cmdType=…}`——执行耗时分布（批量执行器记整批一次）
- 无 micrometer 时自动降级 NOOP（零开销）；自定义监控体系实现 `AsyncCommandMetrics` Bean 即接管
- 告警建议：`dead > 0` 立即告警（与健康端点"死信即 DOWN"同语义）；retry 速率突增预示下游故障

### 健康与管控

- **健康端点**（引 actuator 即有）：`starsiteSequence`（形态/机器位，被动上报）；`starsiteAsyncCommand`（各状态计数 + 到期积压，**存在死信即 DOWN**）
- **管控端点鉴权**（三级，优先级从高到低）：① 自定义 `AsyncCommandAdminGuard` Bean（接内部鉴权体系）；② 配置 `admin-token` 启用内置守卫——校验请求头 `X-Starsite-Admin-Token`（常量时间比对防时序侧信道）；③ 都不配则放行 + 启动 WARN（仅本地开发）
- **管控端点**（默认关）：

```yaml
starsite:
  async-command:
    admin-enabled: true          # Web 应用才装配
    admin-base-path: /api/admin/async
```

`GET stats` · `GET recent` · `GET list`（过滤分页）· `POST {id}/retry` · `POST {id}/requeue`（悬挂打回）· `POST {id}/kill`（终止）

### 全量配置（前缀 starsite.async-command）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关 |
| `store` | jdbc | jdbc / memory |
| `table-name` | async_command | jdbc 命令表 |
| `init-ddl` | true | 自动建表 |
| `schedule-mode` | COMPETING | COMPETING / LEADER / EXTERNAL |
| `schedule-lock-ttl-ms` | 30000 | LEADER 选主锁 TTL |
| `poll-interval-ms` | 5000 | 调度轮询间隔 |
| `batch-size` | 10 | 单轮认领批量 |
| `stale-after-seconds` | 300 | RUNNING 悬挂判定 |
| `default-max-retry` | 5 | 重试上限（超限 DEAD） |
| `backoff-base-seconds` | 60 | 退避基数 |
| `backoff-max-seconds` | 3600 | 退避封顶 |
| `shared-pool-core-size` | 4 | 共享池核心线程 |
| `shared-pool-max-size` | 16 | 共享池最大线程 |
| `shared-pool-queue-capacity` | 1000 | 共享池队列（满则 CallerRuns 反压） |
| `admin-enabled` | false | 管控端点开关 |

## ③ lock：分布式锁

多实例互斥的标准件：DB 唯一键形态（无 Redis 依赖）与 Redis 形态（SETNX + TTL + Lua 释放）一套 API，
async-command 的 LEADER 选主也走它。

```yaml
starsite:
  lock:
    mode: db              # 默认；需 MyBatis 环境 + DataSource + sequence starter（锁行主键发号）
    # mode: redis         # 需 StringRedisTemplate
    table-name: starsite_lock
    ttl-ms: 30000         # 持锁 TTL（过期即让位——锁持有者崩溃后最长等一个 TTL）
    # owner-id:           # 缺省回填 应用名@pid
    # redis-key-prefix: starsite:lock:
```

```java
@RequiredArgsConstructor
class NightlyJob {
    private final LockClient lockClient;

    void run() {
        if (lockClient.tryLock("nightly-settle", 3_000)) {  // 限时等待：3s 内他方释放则获得；0=非阻塞

            try {
                // 只有持锁实例进入（TTL 内其余实例 tryLock 返回 false）
            } finally {
                lockClient.unlock("nightly-settle");    // 仅持锁者能释放
            }
        }
    }
}
```

- **DB 形态**：`lock_name` 唯一键单行 UPDATE 原子抢占（空/过期/自己持锁三种命中）；种子播种有实例内记忆（性能提示而非正确性依据）——未抢到时校验锁行存活性，外部删行自动重播种重试自愈，无需重启
- **限时等待获取**：`tryLock(name, waitMillis)`——立即尝试 + 窗口内轮询重试（100ms 间隔；无公平性，DB fail-open 逐次生效；中断恢复标记返回 false）
- **可重入（ownerId 粒度，存储端计数）**：持有层数存于锁表 hold_count 列（DB）/Redis hash（field=ownerId）——同 ownerId 跨实例共享同一份计数，嵌套作用域的内层 unlock 只递减，归零才真正释放
- **SQL 全索引命中**：锁表仅 PK(id)+uk(lock_name) 最小索引集（争锁/释放均为单条原子 UPDATE，含重入计数递增/递减），争锁/释放/查询全部 lock_name 点查，无扫描无慢 SQL（索引完备性有单测断言）
- **Redis 形态**：`SET key value NX PX ttl` + 校验 value 的 Lua 原子释放（防误删他方锁）
- **fail-open**：锁存储异常时放行（宁可多实例竞争不停摆，上层幂等兜底），WARN 可见

### 全量配置（前缀 starsite.lock）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关 |
| `mode` | db | db / redis |
| `table-name` | starsite_lock | db 锁表（通用互斥，不绑定调度场景） |
| `ttl-ms` | 30000 | 持锁 TTL |
| `owner-id` | 应用名@pid | 持锁者标识 |
| `redis-key-prefix` | starsite:lock: | redis 键前缀 |
| `init-ddl` | true | db 形态自动建表 |

## 构建与测试

当前全量 **118 项单测**（6 模块全绿；数字随测试演进，以 `mvn verify` 汇总行实时值为准——本行为对外镜像面的权威声明口径）。

```bash
mvn verify          # 规范检查 + 单测全量（唯一性/回拨/回绕/重试/死信/幂等/认领互斥/悬挂回收/装配/生命周期/锁互斥）
mvn verify -Pit     # 真库 IT（Testcontainers MySQL）：LAST_INSERT_ID 原子租约、令牌认领并发、悬挂回收
mvn spotless:apply  # 规范自动修复（许可头/未用导入/空白）；verify 阶段做 check 门禁
```

- IT 需要 docker 与 mysql:8.0 镜像；macOS Docker Desktop 若 socket 非标准路径，
  先 `sudo ln -sf ~/.docker/run/docker.sock /var/run/docker.sock`
- 无 docker 环境：设 `TEST_MYSQL_URL`（配 `TEST_MYSQL_USER`/`TEST_MYSQL_PASSWORD`，默认 root/root）
  直接对外部库跑 IT——例如 `TEST_MYSQL_URL=jdbc:mysql://127.0.0.1:3306/test mvn verify -Pit`

## 代码规范（机器把关，不靠自觉）

- **Spotless 门禁**（`mvn verify` 强制）：Apache-2.0 许可头、未用导入清理、行尾空白、文件末换行
- **Javadoc 约定**：公开类/接口必带职责说明与 `@author`/`@since`；注释写"为什么"（时钟为何钳制、悬挂为何用库时钟），不复述代码
- **可维护性铁律**：魔数必须提常量并注释语义（如停机排空上限/轮询下限）；测试钩子（假时钟）只存在于 `src/test`；测试确定性优先——不 sleep、不碰运气
- **CI**（GitHub Actions，ubuntu 自带 docker）：push/PR 自动跑规范检查 + 单测 + 真库 IT，红了不许合
- License：[Apache-2.0](LICENSE)；`.editorconfig` 统一跨编辑器基础格式

## 后续规划

- [x] async-command：批量执行器、Micrometer 指标、管理端鉴权、分布式调度三模式
- [x] star-site-backend 生产接入（发号 + AI 解读异步命令 + workerId 租约，包结构与门面已对齐）
- [ ] sequence：Redis 租约实现（无 MySQL 场景）、K8s StatefulSet 序号直取
- [ ] lock：看门狗自动续期（长任务持锁跨 TTL 场景）
