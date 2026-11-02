# schedule · 分布式定时任务恰一执行

> **状态：设计稿 v0.1（2026-11-02，未实现——经 invest S09 生产实证后提炼）**
> [← 返回概览](../README.md)

**一句话**：注解驱动的定时任务"恰一执行"组件——每实例都配同一 cron，但每个触发点**全集群只跑一次**。

```
所有实例同一时刻醒来（cron 本地计算）
   └─► 原子争锁（复用 lock 组件）
         ├─ 获锁者：执行业务 → 写执行流水 → 释放
         ├─ 未获锁者：写 SKIPPED_LOCKED 流水 → 静默跳过
         └─ 持锁者崩溃：TTL 过期 → 下轮自动接管（自愈）
```

---

## 1. 从生产代码长出来的组件

star-site-backend 已在生产手工重复这套样板（ReminderScanTask 等）：

```java
@Scheduled(cron = "${reminder.scan-cron:0 0 8 * * ?}")
public void scan() {
    if (!lockClient.tryLock(LOCK_NAME)) {      // ← 样板①：争锁判断
        log.info("每日提醒扫描跳过：他方实例持锁执行中");
        return;
    }
    try {
        reminderService.scanAndNotify();       // ← 业务
    } finally {
        lockClient.unlock(LOCK_NAME);          // ← 样板②：释放
    }
}
```

每加一个定时任务就抄一遍；锁名随手起、执行无流水、错过不补跑、时区靠运气。
schedule 组件把这三行样板、观测、错过策略全部吸进注解。

invest（Python）S09 已用同一锁协议上线（每晚 20:00 双容器恰一拉数，跨容器互斥
已实测）——本组件是该协议的 Java 产品化，双语言共存规范见 §8。

## 2. 定位与边界

**先例**：设计思路致敬 [ShedLock](https://github.com/lukas-krecan/ShedLock)
（annotation + lock provider + keepAlive），差异点：与 lock/sequence 组件原生协同
（看门狗续期/发号）、中文文档、双语言协议、失败语义产品化（§6 misfire）。

| 场景 | 用本组件 | 不用 |
|---|---|---|
| 每日定时报表/扫描/拉数，多实例部署怕重跑 | ✅ 核心场景 | |
| 高频轮询（秒级 fixedDelay）恰一 | ✅（FixedDelay 触发器） | |
| 失败重试/退避/死信 | ❌ 这是 async-command 的领域 | |
| 任务分片/DAG 依赖/工作流 | ❌ XXL-JOB/PowerJob 领域 | |
| 秒级精度/海量任务/web 控制台 | ❌ 演进路径见 §9 | |

**正交组合**：`@StarsiteScheduled` 方法体里可以 `asyncCommandClient.submit(...)`
——"定时触发 + 失败落异步命令重试"是预期组合拳，不互相替代。

## 3. 四层模型

| 层 | 职责 | 载体 |
|---|---|---|
| **触发**（when） | cron/fixedDelay/dailyAt 本地计算下一刻 | 每实例独立，无需中心 |
| **决策**（who） | 原子争锁 → 恰一 | **复用 lock 组件**（DB/Redis 双形态、看门狗、TTL 自愈） |
| **执行**（what） | 业务方法 + 超时保护 + 异常吞噬 | 组件内单线程/共享池（沿用 async-command 调度器形态） |
| **观测**（how） | 执行流水 + admin + metrics | `schedule_run_log` 表 + RunLogAdminService |

分层要义：**触发与执行可分离**——未来中心化调度服务接管触发（§9），执行侧
协议（锁 + 流水）不变，注解任务零改动迁移。

## 4. 目标 API

### 4.1 注解（业务方唯一入口）

```java
@StarsiteScheduled(
    name = "reminder-scan",              // 任务名（默认：类#方法短名）；锁名 = "sch:" + name
    cron = "0 0 8 * * ?",                // 触发器三选一：cron / dailyAt="08:00" / fixedDelaySeconds=60
    zone = "Asia/Shanghai",              // 默认 Asia/Shanghai（UTC 容器踩坑产品化，见 §6）
    lockTtlSeconds = 1800,               // 锁 TTL 下限；执行中看门狗自动续期（复用 lock 能力）
    misfire = MisfirePolicy.SKIP,        // SKIP | RUN_ONCE（错过策略，见 §6）
    guard = true,                        // false=退化为本地调度（每实例都跑，单机开发用）
    timeoutSeconds = 0                   // 0=不限时；超时中断线程并标 FAILED（锁由 TTL 兜底释放）
)
public void scan() { ... }
```

### 4.2 门面（编程式触发与观测）

```java
public interface ScheduleClient {
    boolean triggerNow(String name);          // 手动触发（走同一锁协议——不会与定时触发双跑）
    List<TaskStatus> tasks();                 // 全部任务：name/cron/zone/lastRun/nextRun/state
    Optional<TaskStatus> status(String name);
}

public interface RunLogAdminService {          // 观测/运维
    List<RunLog> recentRuns(String taskName, int limit);   // 列表列（无 error 大列，D6 分列铁律）
    Optional<RunLog> runDetail(long id);                    // 详情含 error
    List<RunLog> staleRunning(Duration olderThan);          // 悬挂 RUNNING 巡检
}
```

## 5. 存储设计

### 5.1 schedule_run_log（唯一新表）

```sql
CREATE TABLE schedule_run_log (
    id            BIGINT       NOT NULL,   -- sequence 发号（D2 铁律，禁 AUTO_INCREMENT）
    task_name     VARCHAR(64)  NOT NULL,
    lock_name     VARCHAR(128) NOT NULL,
    trigger_time  DATETIME     NOT NULL,   -- 本次计划触发时刻（misfire 判定基准）
    started_at    DATETIME     NOT NULL,
    finished_at   DATETIME     NULL,
    host          VARCHAR(128) NOT NULL,   -- hostname:pid（对齐 invest holder 语义）
    status        VARCHAR(16)  NOT NULL,   -- RUNNING / DONE / FAILED / SKIPPED_LOCKED / SKIPPED_MISFIRE / STALE
    duration_ms   BIGINT       NULL,
    gmt_create    DATETIME     NOT NULL,
    gmt_modified  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_srl_task_time (task_name, trigger_time),
    KEY idx_srl_status (status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- error TEXT 列在实现时补（详情列不进列表查询——D6）
```

锁**不建新表**——直接用 lock 组件的 starsite_lock（锁名 `sch:<name>` 前缀隔离
业务手工锁）。SQL 外置铁律（D5）：MyBatis Mapper XML 同包路径 + JDBC 方言
`resources/sql/mysql/schedule-mysql.xml`，Java 零 SQL 字符串。

### 5.2 store 三形态（策略模式，与三组件同构）

`memory`（单机观测/测试）→ `jdbc` → `mybatis`（classpath 自动探测，消费方零配置）。

## 6. 语义细则（每个都是实证踩坑的产品化）

| 语义 | 决策 | 出处 |
|---|---|---|
| **恰一** | 同刻多实例争锁，UNIQUE(lock_name) 兜底恰一；未获锁写 SKIPPED_LOCKED 流水（跳过可观测，不是黑盒） | invest S09 8 线程实测恰 1 |
| **崩溃自愈** | 持锁者宕机不释放 → TTL 过期接管；RUNNING 超过 TTL+宽限 → 巡检标 STALE（不误杀长任务：看门狗在续期的任务持有未来时间戳） | lock 组件既有能力 |
| **看门狗续期** | lockTtlSeconds 是下限不是死线；长任务自动续期，跑完/崩溃才放 | WatchdogLockClient 既有 |
| **misfire** | `SKIP`：错过就错过（默认，幂等数据任务安全）；`RUN_ONCE`：启动时对比 run_log 最近成功 trigger_time 与 cron 推算，缺的补跑一次（只补一次，不回放） | invest 已知取舍"不回跑"在此升级为可选 |
| **时区** | zone 默认 Asia/Shanghai 写死在注解默认值——**容器 UTC 陷阱**（hour=20 实际凌晨 4 点触发）实证过两次（S09 + 部署脚本），产品化即默认正确 | invest S09 |
| **fail-open** | 决策层继承 lock DB 形态 fail-open：锁存储异常时放行执行 → **契约：任务体必须幂等**（写进 javadoc 与 README，极端双跑可容忍；数据面幂等 = 与 invest 同取舍） | lock D 决策 |
| **手动触发** | triggerNow 与定时触发共用锁与流水，天然防"手动+定时撞车" | invest 手动拉数不参与锁的教训 |
| **异常吞噬** | 任务抛异常 → FAILED + error 详情 + 不打断调度线程；下一轮照常 | async-command 同语义 |

## 7. 配置（starter 自动装配）

```yaml
starsite:
  schedule:
    enabled: true            # false=组件完全让位（WebFlux 网关等无 DB 场景）
    store: auto              # auto=classpath 探测 mybatis/jdbc；memory 显式指定
    pool-size: 2             # 触发调度线程数（单线程串行默认，任务各自独立线程执行）
    clock: system            # 测试可注入假时钟（TimeSource 模式沿用）
```

## 8. 双语言协议（invest Python 参考实现）

Java 与 Python 任务可共存同一 MySQL 互不干扰、互可观测，遵循三条协议：

1. **锁名规范** `sch:<app>:<task>`（invest 现用 `invest-pull` 属过渡名，升级时兼容映射）
2. **锁时间戳** 微秒 ISO 字符串（invest S09 实现口径；Java 侧 DbLockClient 既有 DATETIME——**互通需统一时间戳类型**，列为 v0.2 议题，v0.1 双语言仅要求"同库不冲突"）
3. **状态枚举一致**：RUNNING/DONE/FAILED/SKIPPED_LOCKED/STALE（invest 侧 `ran/reason` 可映射）

## 9. 演进路径（何时换 XXL-JOB）

任务数 > ~50 / 需要 web 控制台 / 需要 worker 分片 / 秒级精度 → 上中心调度服务。
本组件已把触发与执行分离：`star-schedule-admin`（未来）接管触发、复用同一
run_log/lock 表与锁名协议，**注解任务零改动迁移**。在那之前，嵌入式恰一执行
是个人/小团队形态的最优解（零新部署、零新中间件）。

## 10. 模块结构与实现顺序

```
starsite-schedule/            core：零 Spring 依赖
  ├─ api/        StarsiteScheduled, MisfirePolicy, ScheduleClient, RunLogAdminService
  ├─ trigger/    Trigger 接口 + CronTrigger(自研 6 字段) + DailyAtTrigger + FixedDelayTrigger
  ├─ core/       ScheduleTaskRegistry + ExactlyOnceRunner(决策层：锁包装+流水+看门狗)
  ├─ store/      RunLogStore 接口 + Memory/Jdbc/MyBatis 三实现
  └─ clock/      TimeSource（沿用 async-command 模式）
starsite-schedule-spring-boot-starter/   BeanPostProcessor 扫注解 + SmartLifecycle 托管 + 自动装配
```

**实现顺序**（SDD 三步：Spec(本文) → 实现 → 验收）：
1. core：注解模型 + Trigger（含 cron 解析器）+ ExactlyOnceRunner + memory store
2. jdbc/mybatis store + 建表 DDL（IT：Testcontainers MySQL）
3. starter 装配 + backend ReminderScanTask 试点迁移（生产验收）
4. invest 侧锁名升级 `sch:invest:pull`（协议对齐）

**测试矩阵**（目标 40+ 项，对齐三组件 123 项的口径）：

| 断言 | 方法 |
|---|---|
| N 实例同刻恰一（1 RUNNING，其余 SKIPPED_LOCKED） | 并发触发 + memory/jdbc 双 store |
| TTL 过期接管 / RUNNING 悬挂标 STALE | 假时钟推进 |
| misfire RUN_ONCE 只补一次 | 假时钟 + 预置 run_log |
| 看门狗续期（长任务不误杀） | 假时钟 |
| UTC 容器 20:00 CST 触发（时区默认值） | zone 断言 |
| triggerNow 与定时不双跑 | 先手动后到点 |
| fail-open 双跑声明（幂等契约文档化） | store 抛异常注入 |
| cron 解析器与 Spring 语义等价 | 边界值用例集 |
| memory/jdbc/mybatis 行为等价 | 同套用例三跑 |

---

*设计稿评审要点：① cron 自研解析器 vs 引第三方（当前倾向自研，可控+零依赖，
护城河叙事同 sequence 时钟回拨钳制）；② misfire 是否要 CATCH_UP(N)（当前砍掉，
YAGNI）；③ 双语言时间戳互通放 v0.2（当前仅要求同库共存）。*
