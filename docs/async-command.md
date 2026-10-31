# async-command · 异步命令

> [← 返回概览](../README.md)

提交方在业务事务内 `submit`（命令与业务变更同事务原子落库，`AsyncCommandTxTemplate` 回调式编程事务），
调度器认领执行、指数退避重试、超限死信，崩溃实例的悬挂命令自动回收。

## 有 DB 的服务（单机或分布式通用）

```yaml
starsite:
  async-command:
    mode: mysql          # 默认；表自动创建（async_command，可配表名）；需 sequence starter 发主键
```

多实例部署时各实例独立令牌认领（`UPDATE ... claim_token` 互斥），同一命令只会被一个实例执行。

## 无 DB 的单机服务/工具

```yaml
starsite:
  async-command:
    mode: standalone     # 进程内存队列（重启丢命令，可靠性链路用 mysql）
```

## 使用

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

## 执行模式（三选一，执行器声明）

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

## 批量执行器（攒批更高效的场景）

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

## 分布式调度模式（schedule-mode）

| 模式 | 语义 | 适用 |
|---|---|---|
| `COMPETING`（默认） | 各实例独立轮询令牌认领，天然分摊 | 通用；命令执行幂等即可 |
| `LEADER` | 经 lock 组件选主，**只有 leader 调度**（注入 `LockClient` Bean 即自动使用） | 命令必须单点顺序消费（对账/汇总类） |
| `EXTERNAL` | 组件不自跑调度，由外部触发器调 `/tick` 端点 | XXL-Job / K8s CronJob 统一编排的场景 |

## 指标（引 actuator/micrometer 即自动生效）

- Counter `starsite.async.commands{status=submitted|success|retry|dead, cmdType=…}`——命令生命周期四态计数
- Timer `starsite.async.execution{cmdType=…}`——执行耗时分布（批量执行器记整批一次）
- 无 micrometer 时自动降级 NOOP（零开销）；自定义监控体系实现 `AsyncCommandMetrics` Bean 即接管
- 告警建议：`dead > 0` 立即告警（与健康端点"死信即 DOWN"同语义）；retry 速率突增预示下游故障

## 健康与管控

- **健康端点**（引 actuator 即有）：`starsiteAsyncCommand`（各状态计数 + 到期积压，**存在死信即 DOWN**）
- **管控端点鉴权**（三级，优先级从高到低）：① 自定义 `AsyncCommandAdminGuard` Bean（接内部鉴权体系）；② 配置 `admin-token` 启用内置守卫——校验请求头 `X-Starsite-Admin-Token`（常量时间比对防时序侧信道）；③ 都不配则放行 + 启动 WARN（仅本地开发）
- **管控端点**（默认关）：

```yaml
starsite:
  async-command:
    admin-enabled: true          # Web 应用才装配
    admin-base-path: /api/admin/async
```

`GET stats` · `GET recent` · `GET list`（过滤分页）· `POST {id}/retry` · `POST {id}/requeue`（悬挂打回）· `POST {id}/kill`（终止）

## 全量配置（前缀 starsite.async-command）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关 |
| `mode` | mysql | mysql / standalone |
| `table-name` | async_command | mysql 命令表 |
| `init-ddl` | true | 自动建表 |
| `schedule-mode` | COMPETING | COMPETING / LEADER / EXTERNAL |
| `schedule-lock-ttl-ms` | 30000 | LEADER 选主锁 TTL |
| `poll-interval-ms` | 5000 | 调度轮询间隔 |
| `batch-size` | 50 | 单轮认领批量（满批追赶，上限 8 轮/tick） |
| `stale-after-seconds` | 300 | RUNNING 悬挂判定 |
| `default-max-retry` | 5 | 重试上限（超限 DEAD） |
| `backoff-base-seconds` | 60 | 退避基数 |
| `backoff-max-seconds` | 3600 | 退避封顶 |
| `retention-enabled` | false | 终态行清理开关（**生产强烈建议开启**——终态行无清理将无限累积，OBS-111） |
| `retention-days` | 7 | 终态保留天数（对账/审计需求定下限） |
| `retention-batch-size` | 500 | 单轮清理批量（满批追赶，单窗口最多 20 轮） |
| `shared-pool-core-size` | 4 | 共享池核心线程 |
| `shared-pool-max-size` | 16 | 共享池最大线程 |
| `shared-pool-queue-capacity` | 1000 | 共享池队列（满则 CallerRuns 反压） |
| `admin-enabled` | false | 管控端点开关 |
