# idempotent · API 幂等键（Stripe 同款语义）

> **状态：已实现 v0.1 + redis 形态（2026-11-13——core+starter 双模块 60 项测试全绿；真库 IT 见 JdbcIdempotentStoreIT）**
> [← 返回概览](../README.md)

**一句话**：`@StarsiteIdempotent(key = "#orderId")`——同一键的重复请求只执行一次，
**首次成功的结果直接回放**（不只是拦截）。网络重试、用户双击、网关重发、MQ 重复投递的统一解。

```
第一个请求（认领者）                          重复请求（同键，窗口内）
   │ INSERT uk(scope,idem_key) 冲突即让位        │
   ▼                                            ▼
 PROCESSING ──成功──► DONE ──expire_at 内──► 回放首次 result（动作零执行）
   │                  └──expire_at 过期──► 键遗忘 → 重新执行
   ├──抛异常──► FAILED（键释放）──新请求──► 回收再执行
   └──认领者崩溃──► PROCESSING 停滞超阈值──► 下一个请求回收再执行（自愈）
```

---

## 1. 从痛点长出来的组件

每个写过支付/下单接口的人都被教育过："前端按钮置灰 + 后端再防一道"。后端那道防线
常见的三种写法各有坑：

| 常见写法 | 坑 |
|---|---|
| 查订单号存在则拒绝 | **查插竞态**：两个并发请求同时查到"不存在"，双双落库 |
| Redis SETNX 占位 | 占位与业务落库**非原子**；占位后崩溃 = 键泄漏，用户永远提交不了 |
| 唯一索引兜底 | 只能拒绝（异常 500），**没有"返回首次结果"的能力**——用户重试拿到的是报错不是订单 |

Stripe 的 idempotency key 语义是完整答案：**重复请求回放首次成功的结果**——用户第 3 次
点击重试时，拿到的是同一个订单号和"成功"，而不是异常。本组件把这套语义做成一行注解。

## 2. 定位与边界

**先例**：设计思路致敬 [Stripe Idempotency Keys](https://docs.stripe.com/api/idempotent_requests)
（24h 键记忆 / 结果回放 / 5xx 释放键），差异点：注解级 DX（Stripe 是请求头协议）、
零新中间件（uk 原子认领，无需 Redis）、与 sequence/async-command 同库共生（发号/清理看护复用）。

| 场景 | 用本组件 | 不用 |
|---|---|---|
| 下单/支付/表单提交防重复，**重试要拿到首次结果** | ✅ 核心场景 | |
| 导出/报表类慢接口防并发重复触发（字面量键=单飞闸） | ✅ | |
| 消费侧幂等（MQ 去重） | ⚠️ 可用但建议业务表唯一索引（消费偏移自有语义） | |
| 高频读接口缓存 | ❌ 这是缓存的领域（结果窗口 24h，不是 TTL 缓存） | |

**与 async-command `submitIdempotent` 的边界**：那是异步命令**提交侧**去重
（防止同一业务事件重复入队）；本组件是同步 API **请求侧**结果回放（防止重复请求重复执行）。

## 3. 使用

### 注解路径（web 服务）

```xml
<dependency>
    <groupId>com.xp.starsite</groupId>
    <artifactId>starsite-idempotent-spring-boot-starter</artifactId>
    <version>0.2.3</version>
</dependency>
```

```java
@PostMapping("/orders")
@StarsiteIdempotent(key = "#request.orderId")     // SpEL 取参数；同 orderId 窗口内只执行一次
public OrderResult create(@RequestBody CreateRequest request) {
    return orderService.create(request);          // 首次执行；重复请求直接回放首次返回值
}
```

键表达式三形态：

```java
@StarsiteIdempotent(key = "#orderId")                    // ① SpEL 参数引用（Boot 3.2+ 默认 -parameters）
@StarsiteIdempotent(key = "'export-' + #day")            // ② SpEL 拼接
@StarsiteIdempotent(key = "${settle.idem-key}")          // ③ 字面量 + ${} 占位符（yml 集中管理）
```

- `scope` 缺省 `类简单名#方法名`——同一个 orderId 在"下单"和"支付"是两个键（隔离）；
  参数名不可反射时可用位置引用 `#p0`
- 在飞冲突抛 `IdempotentConflictException`（web 侧建议映射 409）；等待目睹他方失败抛
  `IdempotentFailedException`（携带失败摘要）
- 回放经 Jackson 序列化往返——返回值须可 JSON 化（DTO 常态；`void` 合法）

### 门面路径（编程式 / 非 Spring 方法）

```java
@RequiredArgsConstructor
class SettleService {
    private final IdempotentClient idempotent;    // 注入即用

    void settle(String day) {
        IdempotentResult r = idempotent.guard("daily-settle", day, () -> {
            doSettle(day);                        // 同键恰一执行，窗口内重复调用零执行
            return "ok-" + day;
        });
        if (r.getKind() == IdempotentResult.Kind.CONFLICT) { /* 他方在飞，稍后重试 */ }
    }
}
```

门面路径业务自管载荷字符串（core 零 JSON 依赖）；注解路径的序列化在 starter。

## 4. 语义细则（每个都是刻意决策）

| 语义 | 决策 | 为什么 |
|---|---|---|
| **结果回放** | DONE 且未过 `expire_at` 的重复请求返回存储的 result 载荷，动作零执行 | 幂等键与缓存/拦截的分水岭——重试拿到的是首次成功结果 |
| **键记忆窗口** | `key-ttl-hours` 默认 24h（Stripe 同款）；过期即"键遗忘"重新执行 | 无限记忆 = 无限存储；24h 覆盖人类重试行为的全部现实跨度 |
| **失败释放键** | 执行抛异常 → FAILED，**新请求重新执行**（不是回放失败） | 失败可重试是幂等键与缓存的关键差异（Stripe 5xx 同款）；回放失败会把瞬时故障固化成永久失败 |
| **等待目睹失败不重跑** | 在飞等待中目睹他方 FAILED → 返回失败摘要，不自动回收重执行 | 失败动作的 N 个并发等待者同时重跑 = 重试风暴；显式重试（新请求）才走回收 |
| **在飞等待** | 同键 PROCESSING（新鲜）→ 轮询等待至 `wait-millis`（默认 3s），超时 CONFLICT | 双击的第二个请求等 200ms 即回放首个结果（用户体验）；长执行不无限拖死重复方 |
| **崩溃自愈** | 认领者崩溃 → PROCESSING 的 gmt_modified 停滞超 `processing-stale-seconds`（默认 120s）→ 下一个请求条件回收再执行 | 惰性自愈（新请求驱动），零后台扫描——与 lock 行丢失自愈同哲学 |
| **代际令牌守卫** | complete/fail 双条件（PROCESSING **且** expire_at=本代值）——被回收的行拒绝旧认领者终态写入 | 回收是原地 UPDATE，仅按 status 守卫分不出新旧认领者；expire_at 每代必换是免费的代际标识 |
| **认领互斥** | uk(scope, idem_key) INSERT 原子性（表形态）/ compute 原子性（memory） | 查插竞态的根治——存在性判定与占位是同一条原子语句 |
| **冲突异常** | core 定义 `IdempotentConflictException` / `IdempotentFailedException`（零框架依赖），409 映射交给消费方 | starter 不引 spring-web（core/starter 双模块铁律） |

**契约**：动作（注解方法/门面 lambda）的副作用应可接受"恰一执行"语义——即同一键的
重复请求**不**触发第二次副作用（这正是组件承诺并保证的）；但**不承诺**跨键的并发互斥。

## 5. 存储与形式

### 表 idempotent_record（唯一新表，自动建表）

```sql
CREATE TABLE IF NOT EXISTS idempotent_record (
    id            BIGINT       NOT NULL,          -- sequence 发号（D2 铁律，禁 AUTO_INCREMENT）
    scope         VARCHAR(64)  NOT NULL DEFAULT '',
    idem_key      VARCHAR(128) NOT NULL,
    status        VARCHAR(16)  NOT NULL,          -- PROCESSING / DONE / FAILED
    result        TEXT         NULL,              -- 回放载荷（详情列，点查全列——本组件无列表查询）
    error         VARCHAR(500) NULL,              -- 失败摘要（详情列）
    expire_at     DATETIME     NOT NULL,          -- 键记忆窗口边界
    finished_at   DATETIME     NULL,
    host          VARCHAR(128) NOT NULL,          -- 认领实例 hostname:pid
    gmt_create    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idem_scope_key (scope, idem_key),   -- 认领互斥锚
    KEY idx_idem_expire (expire_at)                   -- retention 清理锚
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

SQL 配置外置铁律（D5）：JDBC 方言 `resources/dialect/mysql/idempotent-mysql.xml`；MyBatis
Mapper XML 同包路径；生产 DDL `resources/sql/mysql/idempotent_record_ddl.sql`。建表带
结构校验（BUG-009 惯例：表存在但缺契约列 → 启动期 fail-fast 含迁移指引）。

### store 四形态（策略模式，与家族组件同构）

| 形态 | 语义 | 适用 |
|---|---|---|
| `memory` | ConcurrentHashMap + compute 原子 | 单机/测试（重启丢记录——键记忆随进程消亡） |
| `jdbc` | MySQL 方言，uk INSERT 认领 | 有 DataSource 的默认形态 |
| `mybatis` | Mapper XML 同包自动加载 | 消费方 classpath 自带 mybatis（`store: mybatis` 显式指定） |
| `redis` | hash 六字段 + 四段 Lua 原生原子 + **TTL 即键记忆窗口** | 已有 Redis 无 MySQL 的场景（与 lock/sequence 的 redis 形态同家族定位） |

**redis 形态细节**：每键一个 hash（status/result/error/expire_at/host/gmt_modified），
认领=EXISTS+HSET 单脚本（SET NX 语义）、回收=三合一条件脚本、终态=双条件守卫脚本——
与表形态逐条语义对齐；**键记忆窗口直接用 Redis TTL**（认领时 PEXPIRE，TTL 归零即
"键遗忘"，过期清理由 Redis 原生承担——retention 看护对其自动无操作）。观测降级：
无全库计数（SCAN 代价与健康端点不匹配），countByStatus 保持 -1、健康端点省略计数
明细（与 sequence redis 形态"审计不如 MySQL 行可查"同取舍）。时间值用**定长 ISO 串**
（uuuu-MM-dd'T'HH:mm:ss.SSS）做 Lua 比较——字节序即时间序，免 epoch 换算与时区陷阱。

## 5.5 第三方存储扩展（公司自建库接入指南）

> 本节是幂等组件的扩展细节；**全组件（五件套）扩展点清单与落地检查清单见
> [extension.md](extension.md)**——三层通道选型、Bean 让位机制、内部平台对接示例。

扩展点两层，按存储类型选通道（注册 Bean 即接管装配，零组件改动）：

| 自家存储类型 | 通道 | 做法 |
|---|---|---|
| **MySQL 兼容库**（OceanBase / TBase-MySQL 模式等） | `jdbc` 形态直用 | 连接串指过去即用——SQL 是标准 MySQL 语法（uk + 条件 UPDATE） |
| **类 MySQL 但方言有差异**（TBase PG 模式 / GaussDB 等） | `IdempotentSql` 方言 | 实现 [`IdempotentSql`](../starsite-idempotent/src/main/java/com/xp/starsite/idempotent/store/IdempotentSql.java) 接口（9 个语句），注册为 Bean——自动装配让位，jdbc store 换方言运行 |
| **完全异构存储**（自研 KV / 公司缓存平台 / Tair 等） | `IdempotentStore` SPI | 实现 SPI 四原子操作，注册为 Bean——引擎/注解/看护全链路直接复用 |

SPI 实现的**唯一硬契约**：`tryClaim` / `tryReclaim` / `complete` / `fail` 四操作必须
**存储端原子**（这是多实例恰一执行的全部正确性来源）；观测方法（countByStatus /
purgeExpired）是缺省实现——按自家存储能力选择覆写（不支持就保持降级，别硬扫）。

```java
// TBase（PG 模式）示例：换方言三步
@Bean
IdempotentSql tbaseDialect() {
    return new IdempotentSql() {   // 9 个方法返回 PG 语法语句（ON CONFLICT 替代 uk 冲突等）
        @Override public String insert() { return "INSERT INTO {table} (...) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING"; }
        // ... 其余 8 个方法
    };
}
// starsite.idempotent.store=jdbc——jdbc store 自动用你的方言（Bean 让位机制）
```

```java
// 自研 KV 示例：实现 SPI 注册 Bean
@Bean
IdempotentStore myCompanyStore() {
    return new MyTairIdempotentStore();  // 四原子操作 + 可选观测覆写
}
// 自动装配整体让位；@StarsiteIdempotent 注解、门面、看护、健康端点全链路复用
```

## 6. retention 过期看护

`expire_at` 早于当前时刻的行语义已死（回放/回收判定均不受影响）——默认开（默认 1h 间隔、
批量 500、满批追赶单窗口最多 20 轮）。与 async-command retention 默认关的差异是刻意的：
那边是业务命令数据（不动用户数据的保守默认），这边是瞬态去重状态且过期即死行——
OBS-111（终态行无限累积）的教训提前规避。

## 7. 健康与观测

引 actuator 即有 `starsiteIdempotent`：processing/done/failed 三态计数
（redis 形态观测降级——无全库计数，明细自动省略）。
**FAILED 不判 DOWN**（与 async-command 死信不同——失败是业务动作的自然结局，键已释放
可重试，不是需人工处置的终态积压）；存储访问异常经传播由 actuator 判 DOWN。

## 8. 全量配置（前缀 starsite.idempotent）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关（false=完全让位） |
| `store` | auto | auto / memory / jdbc / mybatis / redis（auto：显式 mybatis 优先，有 DataSource 走 jdbc，否则 memory；**redis 需显式指定 + StringRedisTemplate，缺失启动期快速失败**） |
| `table-name` | idempotent_record | 幂等表名（表形态用） |
| `redis-key-prefix` | starsite:idem: | Redis 键前缀（redis 形态用；多组件共库隔离命名空间） |
| `key-ttl-hours` | 24 | 键记忆窗口（Stripe 同款 24h；redis 形态即 TTL；应大于动作最长耗时——超窗执行会被回收，终态弃写 WARN） |
| `wait-millis` | 3000 | 在飞等待窗口（0=不等待在飞即 CONFLICT） |
| `poll-millis` | 100 | 等待轮询间隔 |
| `processing-stale-seconds` | 120 | PROCESSING 停滞判崩溃遗留阈值（应大于动作最长执行时长） |
| `retention-enabled` | true | 过期行清理看护 |
| `retention-batch-size` | 500 | 单轮清理批量 |
| `purge-interval-minutes` | 60 | 清理看护间隔 |

## 9. 演进路径

- **v0.2 议题**：`Idempotency-Key` 请求头协议模式（web 拦截器形态，键来自请求头而非注解——
  与 Stripe 协议完全对齐）；管理端点（recent/stats/requeue，复用 async-command 三级鉴权）；
  per-scope 配置（不同业务不同窗口）
- ~~Redis 集中形态~~ **已交付（2026-11-13）**：`store=redis`——Lua 四操作 + TTL 键记忆窗口

## 10. 模块结构

```
starsite-idempotent/            core：零 Spring Boot 依赖
  ├─ api/        StarsiteIdempotent, IdempotentClient, IdempotentAction, IdempotentResult,
  │              IdempotentRecord, IdempotentStatus, IdempotentConflictException, IdempotentFailedException
  ├─ core/       IdempotentEngine（认领/等待/回放/回收四态循环——门面实现）
  ├─ store/      契约层（对修改关闭）：IdempotentStore(SPI) + IdempotentSql(方言接口) + ClaimOutcome
  │   ├─ jdbc/     JdbcIdempotentStore（MySQL 方言默认，方言可注入）
  │   ├─ memory/   MemoryIdempotentStore（单机）
  │   ├─ redis/    RedisIdempotentStore（Lua 四操作 + TTL 键记忆窗口）
  │   └─ mybatis/  IdempotentRecordMapper + MyBatisIdempotentStore（新增存储=新子包）
  ├─ resources/  dialect/mysql/idempotent-mysql.xml + sql/idempotent-{claim,reclaim,complete,fail}.lua
  └─ clock/      TimeSource（沿用 async-command/schedule 模式）
starsite-idempotent-spring-boot-starter/
  ├─ IdempotentBeanPostProcessor（注解代理 + SpEL 键 + Jackson 回放）
  ├─ IdempotentAutoConfiguration（memory/jdbc/redis 分发）/ IdempotentMyBatisAutoConfiguration
  ├─ IdempotentRetentionLifecycle（过期看护，SmartLifecycle；redis 形态自动无操作）
  └─ health/IdempotentHealthIndicator（计数降级形态自动省略微报）
```

### 测试矩阵（60 项）

| 断言 | 覆盖 |
|---|---|
| 恰一执行 / 回放零执行 / 并发 8 线程恰 1 执行 N-1 回放 | IdempotentEngineTest ×3 |
| 失败重抛 + FAILED 留痕 / 失败后新请求重执行 / 键遗忘重执行 | IdempotentEngineTest ×3 |
| 崩溃遗留回收自愈 / 在飞超时 CONFLICT / 等待回放 / 等待目睹失败不重跑 | IdempotentEngineTest ×4 |
| 作用域隔离 / 键校验 / purge / **代际令牌旧写拒收** / void 回放 / 多键独立 | IdempotentEngineTest ×6 |
| 认领并发互斥 16 线程恰 1 / 回收三条件 / 代际守卫 / 清理只删过期 / 计数 | MemoryIdempotentStoreTest ×9 |
| Redis：认领脚本契约与键布局 / duplicate 翻译 / hash 解析 / 回收三态翻译 / 终态守卫脚本与 null 载荷标志 / 观测降级 / 前缀缺省 | RedisIdempotentStoreTest ×8 |
| MyBatis 全链路（H2 同构表：认领→守卫→回收→清理）/ 表名白名单 / 陈旧结构 fail-fast / 缺表放行 | MyBatisIdempotentStoreTest ×4 |
| 方言语句键全对齐 / 建表契约（uk/索引/无 AUTO_INCREMENT）/ 语义片段（三合一回收/双条件守卫/占位计数） | IdempotentSqlMySqlTest ×4 |
| 状态机终态/回放/解析 | IdempotentStatusTest ×3 |
| 装配：memory 默认全家桶 / 代理端到端回放 / 冲突映射异常 / enabled 让位 / 自定义 store 让位 / retention 开关 / 显式 memory | IdempotentAutoConfigurationTest ×7 |
| BPP：键三形态（SpEL/字面量/占位符）/ POJO 回放反序列化 / 目睹失败映射异常 / 无注解零代理 | IdempotentBeanPostProcessorTest ×4 |
| redis 装配：模板在场装配 / 缺失快速失败 / 前缀透传 / 健康降级省略微报（含可计数对照） | IdempotentRedisAutoConfigurationTest ×5 |
| 真库：建表 / uk 互斥 / 守卫与回收 / 清理 / 白名单 / 陈旧结构 fail-fast | JdbcIdempotentStoreIT（Testcontainers，-Pit） |
