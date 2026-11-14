# tuner · 参数预案与治理（动态线程池）

> **状态：已实现 v0.1（2026-11-14——core+starter 双模块 21 项测试全绿；真库 IT 见 JdbcTunerStoreIT）**
> [← 返回概览](../README.md)

**一句话**：没有配置中心的团队的动态线程池治理——**预案按时段自动生效 + 变更审计留痕**，
参数源就是 MySQL/内存，零新部署（DynamicTp/Hippo4j 均依赖配置中心或独立 Server）。

```
预案（repayday/daily/batch-window…）
   │ upsert（写入期校验：core≤max / cron 合法 / 预案存在）
   ▼
tuner_preset ──┐
tuner_window ──┤  共享参数源（DB）——多实例轮询同一份
tuner_binding ─┘
   │ 轮询（默认 30s）：version 变更才重载配置；窗口求值每轮都做（时间是自变量）
   ▼
窗口命中求值（纯时间函数）：startCron.nextAfter(t) > endCron.nextAfter(t)
   │ 命中窗口（剩余最长者）> 默认绑定 > 不动（保持基线）
   ▼
条件应用：与池现值对比 → 有差异才调 setter（收缩先降 core 再降 max / 扩张先抬 max）
   ▼
tuner_audit：per-host 变更留痕（10 实例切换=10 行——每行是那台 JVM 的真实账面变化）
```

---

## 1. 为什么是"治理"不是"调参"

金融信贷方法论（docs/tuner-design.md）：通道隔离→容量推演→**日历预案**→健康度联动→**变更治理**→拒绝兜底。DynamicTp/Hippo4j 解决了"怎么调"，本组件补的是信贷/关键系统真正缺的"**何时调（预案日历）、谁批准（审批钩子 v2）、改了什么（per-host 审计）**"。

| 信贷场景 | tuner 用法 |
|---|---|
| 还款日代扣 10 倍洪峰 | `repayday` 预案 + 窗口 cron（每月 10 日 00:00 → 16 日 00:00）自动切换回退 |
| 日终批窗口 | `batch` 预案 + 每日 23:00-24:00 窗口 |
| 合规审计 | tuner_audit 表：谁（host）/何时/旧→新/触发源（SCHEDULE/MANUAL/STARTUP） |

## 2. 使用

### 注册受管池（门面）

```java
@RequiredArgsConstructor
class PoolConfig {
    private final TunerClient tuner;   // 注入即用

    @PostConstruct
    void register() {
        ThreadPoolExecutor daikouPool = new ThreadPoolExecutor(20, 50, 60,
                TimeUnit.SECONDS, new LinkedBlockingQueue<>(1000));
        tuner.register("channel-daikou", daikouPool);   // 注册即快照基线并对齐应生效参数
    }
}
```

### 治理配置（admin 端点或直写 DB）

```bash
curl -X POST localhost:8080/api/admin/tuner/preset -H "X-Starsite-Admin-Token: xxx" \
  -d '{"name":"repayday","core":80,"max":200,"keepAliveSeconds":60}'
curl -X POST .../tuner/window \
  -d '{"poolName":"channel-daikou","presetName":"repayday","startCron":"0 0 0 10 * ?","endCron":"0 0 0 16 * ?"}'
curl -X POST .../tuner/reload   # 立即生效（与自动轮询同一路径，无旁路）
```

### 单机 / 分布式

| | 单机 | 分布式 |
|---|---|---|
| store | memory（零依赖） | jdbc/mybatis（DB 共享参数源） |
| 收敛 | 本 JVM | 各实例独立求值纯时间函数，天然一致 |
| 宕机跨窗口 | 无需 | 状态式求值，重启下一轮自动追平（无 misfire） |

## 3. 语义细则（每个都是刻意决策）

| 语义 | 决策 | 出处 |
|---|---|---|
| 窗口命中 | `startCron.nextAfter(t) > endCron.nextAfter(t)`——只需 nextAfter 无需反推 prev；下一次开始晚于下一次结束 ⟺ 在窗口内 | 复用 schedule cron 解析器（D16） |
| 多窗口重叠 | 命中者中取 endCron.nextAfter 最远（剩余最长）；写入期 WARN 可见 | R1-③ |
| 应用顺序 | **收缩先降 core 再降 max / 扩张先抬 max 再抬 core**（JDK 恒 core≤max；设计期两版误判被测试连抓两次——见 D16） | R1 测试实证 |
| no-op 去重 | 与池现值一致的求值不调 setter 不写审计——治理噪音零容忍 | R1 |
| 审计粒度 | 仅实际变更行（per-host）——10 行切换审计是真实性不是冗余 | R1 |
| versionStamp | 秒级精度契约（表形态同秒多写可能同戳，无害多一轮重载）；memory 严格单调 | 真库 IT 实证 |
| 写入期校验 | cron 语法→预案存在→参数合法全 fail-fast（配置错误暴露在治理面而非运行期）；cron 求值失败按"永不触发"处理（不拖垮业务池，WARN 可见） | R2 |
| 基线 | 无绑定无窗口的注册池保持现状（不猜）——注册时快照即基线 | R3 |

## 4. 配置（starsite.tuner.*）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关 |
| `store` | auto | auto/memory/jdbc/mybatis（分布式治理必须 jdbc/mybatis） |
| `table-prefix` | tuner | 治理四表前缀（_preset/_window/_binding/_audit） |
| `poll-interval-seconds` | 30 | 轮询间隔（窗口生效精度上限） |
| `zone` | Asia/Shanghai | 窗口 cron 求值时区 |
| `admin-enabled` | false | 治理端点开关（Web 应用才装配） |
| `admin-base-path` | /api/admin/tuner | 端点基础路径 |
| `admin-token` | 空 | 内置守卫 token（空=自定义 Guard 或放行+WARN——async 三级鉴权同款） |

## 5. 测试矩阵（21 项 + 真库 IT 5 项）

| 断言 | 覆盖 |
|---|---|
| 无配置保持基线/默认绑定/收缩序/窗口命中切换与回退/边界归属/写入期校验/no-op 去重/启动对齐/并发安全/快照视图 | TunerEngineTest ×10 |
| 方言语句键/建表契约/双载体对齐/memory versionStamp 单调 | TunerSqlMySqlTest ×4 |
| MyBatis H2 CRUD/映射/前缀白名单 | MyBatisTunerStoreTest ×2 |
| 装配：端到端生效/让位/jdbc 形态（H2 四表）/健康明细 | TunerAutoConfigurationTest ×4 |
| 真库：upsert 幂等（ON DUPLICATE KEY）/窗口 CRUD/绑定/审计/versionStamp 秒级精度/前缀白名单 | JdbcTunerStoreIT（-Pit） |

## 6. 演进路径

- **v2**：美团可变容量队列（`ResizableCapacityLinkedBlockingQueue`——队列容量热调）；健康度联动自动切预案（阈值→预案的 SPI 决策规则）；审批钩子 SPI（变更前回调业务侧流程）；审计 retention
- **何时不用**：已有 Nacos/Apollo → DynamicTp 更全（三方中间件池适配深）；池多到要治理平台 → Hippo4j Server
