# 用户手册（新项目接入手册）

> [← 返回概览](../README.md) · 七组件：sequence / async-command / lock / schedule / idempotent / quota / tuner
>
> 本手册回答"新项目要用这套组件，依赖引什么、配置写什么、代码怎么写"——
> 3 分钟接完，逐步按需启用。各组件深入文档见文末链接表。

---

## 1. 三种接入形态（选你的场景）

| 你的环境 | 推荐组合 | 需要的中间件 |
|---|---|---|
| **有 MySQL（最常见）** | `sequence.mode=mysql` + `async.mode=mysql` + `lock.mode=mysql`，其余 auto | MySQL |
| **只有 Redis** | `sequence.mode=redis` + `lock.mode=redis` + `idempotent.store=redis` + `quota.store=redis` | Redis |
| **单机/本地开发** | `sequence.mode=standalone` + `async.mode=standalone`，其余不配（auto→memory） | 无 |

## 2. 依赖引入

```xml
<!-- ① 必引：sequence 是所有组件表物理主键的来源 -->
<dependency>
    <groupId>com.xp.starsite</groupId>
    <artifactId>starsite-sequence-spring-boot-starter</artifactId>
    <version>0.2.3</version>
</dependency>

<!-- ② 按需引（用哪个引哪个） -->
<dependency>
    <groupId>com.xp.starsite</groupId>
    <artifactId>starsite-lock-spring-boot-starter</artifactId>        <!-- 分布式锁 -->
    <version>0.2.3</version>
</dependency>
<dependency>
    <groupId>com.xp.starsite</groupId>
    <artifactId>starsite-async-command-spring-boot-starter</artifactId> <!-- 异步命令 -->
    <version>0.2.3</version>
</dependency>
<dependency>
    <groupId>com.xp.starsite</groupId>
    <artifactId>starsite-schedule-spring-boot-starter</artifactId>     <!-- 定时任务恰一 -->
    <version>0.2.3</version>
</dependency>
<dependency>
    <groupId>com.xp.starsite</groupId>
    <artifactId>starsite-idempotent-spring-boot-starter</artifactId>   <!-- API 幂等键 -->
    <version>0.2.3</version>
</dependency>
<dependency>
    <groupId>com.xp.starsite</groupId>
    <artifactId>starsite-quota-spring-boot-starter</artifactId>        <!-- 余额型配额 -->
    <version>0.2.3</version>
</dependency>
<dependency>
    <groupId>com.xp.starsite</groupId>
    <artifactId>starsite-tuner-spring-boot-starter</artifactId>        <!-- 线程池治理 -->
    <version>0.2.3</version>
</dependency>
```

**lock 的 mysql 形态有隐式依赖**（OBS-103，启动期快速失败会提示）：

```xml
<!-- lock.mode=mysql 需要 MyBatis 环境（lock 的 MySQL 实现走 MyBatis Mapper） -->
<dependency>
    <groupId>org.mybatis.spring.boot</groupId>
    <artifactId>mybatis-spring-boot-starter</artifactId>
    <version>2.3.2</version>   <!-- Boot 2.7 配 2.x；Boot 3.x 配 3.x -->
</dependency>
```

```yaml
# lock mysql 形态需要 mapper 扫描（lock 的 Mapper XML 在同包路径自动加载）
mybatis:
  mapper-locations: classpath:com/xp/starsite/**/mybatis/*.xml
```

## 3. 配置（copy 即用）

### 3.1 有 MySQL 的典型项目

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/your_db?useSSL=false&connectTimeout=5000&socketTimeout=30000
    username: xxx
    password: xxx

starsite:
  sequence:
    mode: mysql                    # 显式：机器位从 sys_sequence 原子租约
  async-command:
    mode: mysql                    # 显式：命令落 async_command 表（自动建）
  lock:
    mode: mysql                    # 显式：走 MyBatis + DataSource
  # 以下 4 个不配 → auto 探测到 DataSource 自动走 jdbc
  schedule:      { }               # schedule_run_log 表自动建
  idempotent:    { }               # idempotent_record 表自动建
  quota:         { }               # quota_account 表自动建
  tuner:         { }               # tuner_preset/window/binding/audit 四表自动建
```

### 3.2 只有 Redis

```yaml
starsite:
  sequence:
    mode: redis
    service-name: ${spring.application.name}
  lock:
    mode: redis
  idempotent:
    store: redis
  quota:
    store: redis
  async-command:
    mode: standalone               # 无 MySQL 用内存（重启丢命令——可靠性需求高请上 MySQL）
```

### 3.3 单机零依赖（本地开发）

```yaml
starsite:
  sequence:
    mode: standalone               # 固定机器位
  async-command:
    mode: standalone
  # lock / schedule / idempotent / quota / tuner 全不配 → auto 降级 memory
```

### 3.4 全量配置键参考

#### sequence（`starsite.sequence.*`）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关（false=完全让位） |
| `mode` | standalone | `standalone` / `mysql` / `redis` |
| `worker-id` | 1 | standalone 固定机器位（多实例各不相同） |
| `service-name` | unnamed-service | mysql 审计行 key |
| `table-name` | sys_sequence | mysql 表名 |
| `init-ddl` | true | 自动建表 |
| `epoch` | 2026-01-01 | 雪花起始时间戳 |
| `backward-policy` | CLAMP | 时钟回拨钳制 / FAIL 拒绝 |

#### async-command（`starsite.async-command.*`）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关 |
| `mode` | mysql | `mysql` / `standalone` |
| `table-name` | async_command | 命令表名 |
| `schedule-mode` | COMPETING | `COMPETING`（各实例分摊）/ `LEADER`（选主单消费）/ `EXTERNAL`（外部 tick） |
| `poll-interval-ms` | 5000 | 调度轮询间隔 |
| `batch-size` | 50 | 单轮认领批量 |
| `default-max-retry` | 5 | 重试上限（超限 DEAD） |
| `backoff-base-seconds` | 60 | 指数退避基数 |
| `retention-enabled` | **false** | 终态行清理（**生产强烈建议开启**） |
| `retention-days` | 7 | 终态保留天数 |

#### lock（`starsite.lock.*`）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关 |
| `mode` | mysql | `mysql` / `redis` |
| `table-name` | starsite_lock | mysql 锁表名 |
| `ttl-ms` | 30000 | 持锁 TTL（持锁者崩溃后最长一个 TTL 让位） |
| `redis-key-prefix` | starsite:lock: | redis 键前缀 |
| `watchdog-enabled` | false | 看门狗自动续期（长任务建议开） |

#### schedule（`starsite.schedule.*`）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关（false=完全让位） |
| `store` | auto | `auto` / `memory` / `jdbc` / `mybatis` |
| `table-name` | schedule_run_log | 流水表名 |
| `pool-size` | 2 | 任务执行池 |
| `stale-threshold-seconds` | 3600 | 悬挂 RUNNING 判死阈值 |

#### idempotent（`starsite.idempotent.*`）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关 |
| `store` | auto | `auto` / `memory` / `jdbc` / `mybatis` / `redis` |
| `table-name` | idempotent_record | 记录表名 |
| `redis-key-prefix` | starsite:idem: | redis 键前缀 |
| `key-ttl-hours` | 24 | 键记忆窗口（Stripe 同款 24h） |
| `wait-millis` | 3000 | 在飞等待窗口 |
| `processing-stale-seconds` | 120 | 崩溃遗留判定阈值 |
| `retention-enabled` | true | 过期行清理看护 |

#### quota（`starsite.quota.*`）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关 |
| `store` | auto | `auto` / `memory` / `jdbc` / `mybatis` / `redis` |
| `table-name` | quota_account | 配额账户表名 |
| `redis-key-prefix` | starsite:quota: | redis 键前缀 |

#### tuner（`starsite.tuner.*`）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关 |
| `store` | auto | `auto` / `memory` / `jdbc` / `mybatis` |
| `table-prefix` | tuner | 治理四表前缀（`_preset`/`_window`/`_binding`/`_audit`） |
| `poll-interval-seconds` | 30 | 轮询间隔（窗口生效精度上限） |
| `zone` | Asia/Shanghai | 窗口 cron 求值时区 |
| `admin-enabled` | false | 治理端点开关（Web 应用才装配） |
| `admin-token` | 空 | 内置守卫 token |
| `alarm-active-ratio` | 0.9 | 活跃度告警阈值 |
| `alarm-queue-ratio` | 0.8 | 队列水位告警阈值 |

## 4. 各组件使用示例

### 4.1 sequence——注入发号器

```java
@RequiredArgsConstructor
class OrderService {
    private final StarsiteIdGenerator idGenerator;   // 注入即用

    Order create() {
        Order o = new Order();
        o.setId(idGenerator.next());                  // long 雪花 ID
        o.setOrderNo(idGenerator.nextStr());          // 字符串形态
        return orderRepo.save(o);
    }
}
```

### 4.2 async-command——提交 + 执行器

```java
// 提交（注入 AsyncCommandClient）
asyncCommandClient.submit("send-notification", userId, "{\"msg\":\"hello\"}");
asyncCommandClient.submitAt("order-cancel", orderId, "{}",
        LocalDateTime.now().plusMinutes(30));         // 延迟队列

// 执行器（实现接口，type 即路由键）
@Component
class NotifyExecutor implements AsyncCommandExecutor {
    @Override public String type() { return "send-notification"; }
    @Override public void execute(AsyncCommand cmd) {
        // 正常返回=成功；抛异常=指数退避重试（60s·2^n 封顶 1h）
    }
}
```

### 4.3 lock——手动互斥

```java
@RequiredArgsConstructor
class NightlyJob {
    private final LockClient lockClient;

    void run() {
        if (lockClient.tryLock("nightly-settle", 3_000)) {
            try {
                // 只有持锁实例进入
            } finally {
                lockClient.unlock("nightly-settle");
            }
        }
    }
}
```

### 4.4 schedule——一行注解

```java
@Component
class ReminderScan {
    @StarsiteScheduled(name = "reminder-scan", cron = "0 0 8 * * ?")
    public void scan() {
        // 全集群恰一执行——未获锁实例自动跳过
        reminderService.scanAndNotify();
    }
}
```

### 4.5 idempotent——防重复提交

注解路径（推荐——web 场景最简）：

```java
@PostMapping("/orders")
@StarsiteIdempotent(key = "#request.orderId")   // 重复请求回放首次结果
public OrderResult create(@RequestBody CreateRequest request) {
    return orderService.create(request);
}
```

门面路径（非 Spring 方法 / 非 web 场景）：

```java
@RequiredArgsConstructor
class SettleService {
    private final IdempotentClient idempotent;   // 注入即用

    String settle(String day) {
        return idempotent.guard("daily-settle", day, () -> {
            doSettle(day);
            return "ok-" + day;
        }).getPayload();                          // EXECUTED=首次载荷 / REPLAYED=回放首次载荷
    }
}
```

### 4.6 quota——防超额

```java
@RequiredArgsConstructor
class LoanService {
    private final QuotaClient quota;

    void disburse(String product, long amount) {
        quota.init("credit:" + product, 1_000_000_00L);   // 幂等建户
        quota.occupy("credit:" + product, amount);          // 超额抛 QuotaExceededException(带可用量)
    }
}
```

### 4.7 tuner——注册池 + 预案

```java
@RequiredArgsConstructor
class PoolConfig {
    private final TunerClient tuner;

    @PostConstruct
    void register() {
        var pool = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
                new ResizableCapacityLinkedBlockingQueue<>(100));   // 可变容量队列
        tuner.register("channel-daikou", pool);
    }
}
// 治理：POST /api/admin/tuner/preset {"name":"repayday","core":80,"max":200,"queueCapacity":5000}
//      POST /api/admin/tuner/window {"poolName":"channel-daikou","presetName":"repayday",
//              "startCron":"0 0 0 10 * ?","endCron":"0 0 0 16 * ?"}
```

## 5. 组合拳（组件协同）

| 场景 | 组合 | 说明 |
|---|---|---|
| 放款防超 + 防重 | quota + idempotent | quota 防"超额"、idempotent 防"重放"——正交互补 |
| 定时 + 异步 | schedule + async-command | `@StarsiteScheduled` 方法体里 `submit(...)`——触发恰一 + 失败重试 |
| 批处理日切 | schedule + quota | 每日 23:00 窗口切 batch 预案 → tuner 池参数自动扩容 |
| 告警联动 | tuner + async-command | tuner 告警钩子 → `submit("send-dingtalk", ...)` → 重试+死信 |

## 6. 排障 FAQ

| 症状 | 原因 | 解法 |
|---|---|---|
| 启动报 `mode=mysql 需要 DataSource` | sequence/async 配了 mysql 但无 DataSource | 加 spring.datasource 或改 standalone |
| 启动报 `lock 需要 ScheduleLockMapper Bean` | lock mysql 走 MyBatis，缺 mapper 装配 | 加 mybatis-spring-boot-starter + mapper-locations |
| 启动报 `store=redis 需要 StringRedisTemplate` | 配了 redis 形态但缺依赖 | 加 spring-boot-starter-data-redis |
| 启动报 `表 xxx 已存在但缺少列` | 数据库有旧表（老版本 DDL） | 按报错指引 ALTER 补列或 DROP 重建 |
| `@StarsiteScheduled` 不生效 | 未引 lock starter（guard=true 需要 LockClient） | 引 starsite-lock-spring-boot-starter |
| `@StarsiteIdempotent` 报"键求值为空" | SpEL 引用的参数为 null | 检查 `#orderId` 参数名拼写与传值 |
| MyBatis 消费方用了组件但建表报错 | MyBatis 未扫到组件的 Mapper XML | 加 `classpath:com/xp/starsite/**/mybatis/*.xml` |
| 组件表建不出来 | `init-ddl=false` 或 DBA 管理表 | 按 `resources/sql/mysql/*.sql` 手工建表 |
| health 端点挂死 | JDBC 超时未配（OBS-106） | URL 加 `connectTimeout=5000&socketTimeout=30000` |

## 7. 生产部署检查清单

- [ ] JDBC 超时已配（`connectTimeout` + `socketTimeout`——否则 DB 故障表现为静默无限挂起）
- [ ] 连接池容量公式：实例数 × 池上限 ≤ MySQL max_connections
- [ ] K8s 探针分离：liveness 用进程级、readiness 用分组 health（避免 DB 抖动连环重启）
- [ ] async-command `retention-enabled: true`（终态行无限累积——OBS-111）
- [ ] lock 关键业务加幂等兜底（fail-open 击穿窗口——OBS-107）
- [ ] 多实例 `worker-id` 各不相同（standalone 形态）

## 8. 组件深入文档

| 组件 | 文档 | 组件 | 文档 |
|---|---|---|---|
| sequence | [sequence.md](sequence.md) | idempotent | [idempotent.md](idempotent.md) |
| async-command | [async-command.md](async-command.md) | quota | [quota.md](quota.md) |
| lock | [lock.md](lock.md) | tuner | [tuner.md](tuner.md) |
| schedule | [schedule.md](schedule.md) | 扩展指南 | [extension.md](extension.md) |

---

*版本：0.2.3 · JDK 8+ · Spring Boot 2.7.x / 3.x 双兼容 · 有问题先看 §6 FAQ，再查各组件深入文档。*
