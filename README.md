# star-site-components

star-site 系列通用组件库：**core（纯库）+ spring-boot-starter（装配）双模块**的开源标准结构，
所有组件同时考虑**单机与分布式**两种运行形态。引 starter + 配置即用；core 可脱离 Spring Boot 独立引用。

```
starsite-sequence ──────► starsite-sequence-spring-boot-starter
  雪花序列核心库              自动装配 + 健康端点
starsite-async-command ─► starsite-async-command-spring-boot-starter
  异步命令核心库              自动装配 + SmartLifecycle + 健康/管控端点
```

| 组件 | 单机形态 | 分布式形态 |
|---|---|---|
| **sequence**（雪花序列） | 固定机器位，零外部依赖 | MySQL sequence 表 `__global__` 池原子租约 |
| **async-command**（异步命令） | memory 进程队列（无 DB） | MySQL 表令牌认领（多实例并发互斥） |

- JDK 21 · Spring Boot 3.2.x · **零 Lombok / 零 MyBatis**（sequence-core 仅依赖 slf4j；async-core 仅多一个 spring-jdbc）
- 设计对齐生产实践复盘：[分布式 ID：从雪花算法到生产实现](https://github.com/Mein-Augenstern/MUYI/blob/master/C-%E6%9E%B6%E6%9E%84/%E5%88%86%E5%B8%83%E5%BC%8F%E7%B3%BB%E7%BB%9F/%E5%88%86%E5%B8%83%E5%BC%8FID/%E5%88%86%E5%B8%83%E5%BC%8FID-%E4%BB%8E%E9%9B%AA%E8%8A%B1%E7%AE%97%E6%B3%95%E5%88%B0%E7%94%9F%E4%BA%A7%E5%AE%9E%E7%8E%B0.md)（star-site-backend 实战回写）
- 与 star-site-backend 的 `sys_sequence` / `async_command` 表结构兼容（可平滑迁移）

## 设计说明（模式与取舍）

| 模式 | 落点 | 解决什么 |
|---|---|---|
| 策略 | `WorkerIdAllocator`（Fixed/Mysql）、`AsyncCommandStore`（Jdbc/InMemory） | 单机/分布式双形态一套 API，`mode`/`store` 一行配置切换 |
| 注册表 | `ExecutorRegistry` | 执行器按 `type()` 注册路由，重复 type **启动期**拒绝（不带病上线） |
| 门面 | `StarsiteIdGenerator` / `AsyncCommandClient` | 业务只面对一个入口，内部结构可演化 |
| 适配器 | starter 的 `AsyncCommandLifecycle`（SmartLifecycle） | core 零 Spring 依赖；容器最后启动/最先停止，不丢尾批任务 |
| 乐观并发 | DB 令牌认领（`UPDATE ... claim_token`） | MySQL 无 `UPDATE..RETURNING` 的经典互斥解，多实例谁写入算谁 |
| 时间注入 | `TimeSource` 可插拔 | 时钟回拨/延迟场景测试确定性构造，测试钩子零侵入主代码 |

其他开源约定：`@ConditionalOnMissingBean` 全量让位（应用自定义 Bean 优先）、
`@ConditionalOnClass` 按classpath 装配（无 actuator 不报错）、`spring-configuration-metadata` 配置提示、
健康检查**被动上报不消耗号段**、测试全部位于 `src/test`（正式代码零测试分支）。

## 快速开始

```xml
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
```

无需 `@Import`、无需 `@EnableScheduling`——自动装配完成一切（自动建表 IF NOT EXISTS 也可关闭交由迁移脚本）。
非 Spring Boot 项目可直接引 core 模块手工组装（`new SnowflakeIdGenerator(...)` / `new AsyncCommandScheduler(...)`）。

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
全平台绝不重复；每个服务实例的占用记录另落审计行。分配值超机器位上限时按 `wraparound-policy` 处理
（默认 RESET 重置并告警，可选 FAIL 拒绝启动）。

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

提交方在业务事务内 `submit`（命令与业务变更同事务原子落库），调度器认领执行、指数退避重试、超限死信，
崩溃实例的悬挂命令自动回收。

### 有 DB 的服务（单机或分布式通用）

```yaml
starsite:
  async-command:
    store: jdbc          # 默认；表自动创建（async_command，可配表名）
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

### 健康与管控

- **健康端点**（引 actuator 即有）：`starsiteSequence`（形态/机器位，被动上报）；`starsiteAsyncCommand`（各状态计数 + 到期积压，**存在死信即 DOWN**）
- **管控端点**（默认关）：

```yaml
starsite:
  async-command:
    admin-enabled: true          # Web 应用才装配；无内置鉴权，确保仅内网/网关白名单可达
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
| `poll-interval-ms` | 5000 | 调度轮询间隔 |
| `batch-size` | 10 | 单轮认领批量 |
| `stale-after-seconds` | 300 | RUNNING 悬挂判定 |
| `default-max-retry` | 5 | 重试上限（超限 DEAD） |
| `backoff-base-seconds` | 60 | 退避基数 |
| `backoff-max-seconds` | 3600 | 退避封顶 |
| `admin-enabled` | false | 管控端点开关 |

## 构建与测试

```bash
mvn verify          # 单测全量（37 个用例：唯一性/回拨/回绕/重试/死信/幂等/认领互斥/悬挂回收/装配/生命周期）
mvn verify -Pit     # 真库 IT（Testcontainers MySQL）：LAST_INSERT_ID 原子租约、令牌认领并发、悬挂回收
```

- IT 需要 docker 与 mysql:8.0 镜像；macOS Docker Desktop 若 socket 非标准路径，
  先 `sudo ln -sf ~/.docker/run/docker.sock /var/run/docker.sock`
- 测试铁律：全部位于 `src/test`；确定性优先（假时钟驱动回拨/延迟，不 sleep 不碰运气）

## 后续规划

- [ ] sequence：Redis 租约实现（无 MySQL 场景）、K8s StatefulSet 序号直取
- [ ] async-command：批量执行器、Micrometer 指标、管理端鉴权钩子
- [ ] star-site-backend 各服务迁移至本组件（替换 common 内置实现，迁移说明另附）
