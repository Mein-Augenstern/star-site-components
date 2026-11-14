# quota · 余额型配额（防超额的通用内核）

> **状态：已实现 v0.1（2026-11-14——core+starter 双模块 35 项测试全绿；真库 IT 见 JdbcQuotaStoreIT）**
> [← 返回概览](../README.md)

**一句话**：余额型资源账户——**占用/释放/冻结/解冻/冻结转占用**五原子操作 +
恒不变式 `occupied + frozen <= total`（多实例并发下超量必拒）。额度/库存/席位/保证金/令牌池同一抽象。

```
                    total（总量）
                        │
   ┌────────────────────┼────────────────────┐
   ▼                    ▼                    
 occupied（已占用）   frozen（冻结中）        
   ▲                    ▲                   
   │ occupy 确定性扣减   │ freeze 预占        
   │ release 占用回退    │ unfreeze 预占释放   
   └── confirm(冻结→占用, 单语句换位) ──┘

不变式 occupied + frozen <= total 在每个操作的 WHERE/条件内原子判定——
超量操作影响行数 0 即被拒绝，无读改写窗口（防超贷/防超卖的同一正确性模型）。
```

---

## 1. 定位与边界

**从信贷场景剥出来的通用内核**：信贷交易的"额度并发占用防超贷"是资损级痛点，但
每家的额度树/冻结原因是自己的业务——把痛点剥开，通用内核就是"带不变式的余额型
账户"。**组件背正确性（不变式+原子操作），业务背含义（配额键组合、冻结原因是业务字段）**。

| 场景 | 用法示例 |
|---|---|
| 信贷支用防超贷 | `init("credit:product-A", 授信额)` → 放款 `occupy` → 失败冲正 `release` |
| 库存防超卖 | `init("inventory:sku-1", 库存)` → 下单 `freeze` → 支付 `confirm` → 取消 `unfreeze` |
| 席位/保证金/令牌池 | 同一 API，键即业务 |

**边界（诚实契约）**：
- 防"超额"不防"重放"——occupy 天然可重复执行，操作幂等由 idempotent 组件守（正交互补）
- 无嵌套额度树——树=业务用多个配额键组合（父键占用+子键分账），组件提供原子底座
- 无计息/费率——那是领域件（repayment-plan），不进通用库

## 2. 使用

### 门面路径

```java
@RequiredArgsConstructor
class LoanService {
    private final QuotaClient quota;   // 注入即用

    void disburse(String product, long amount) {
        quota.init("credit:" + product, 1_000_000_00L);        // 幂等建户
        quota.occupy("credit:" + product, amount);             // 并发下超贷必抛
    }
    // QuotaExceededException.getAvailable() → "还可支用 xx 元" 直接可展示
}
```

### starter（spring-boot）

```xml
<dependency>
    <groupId>com.xp.starsite</groupId>
    <artifactId>starsite-quota-spring-boot-starter</artifactId>
    <version>0.2.3</version>
</dependency>
```

## 3. 五原子操作语义

| 操作 | 条件（WHERE/脚本内原子判定） | 更新 | 业务对应 |
|---|---|---|---|
| `occupy` | `occ + frz + amount <= total` | `occ += amount` | 放款/出货/消耗 |
| `release` | `occ >= amount` | `occ -= amount` | 冲正/退货回补 |
| `freeze` | `occ + frz + amount <= total`（共享池） | `frz += amount` | 下单预占/支付在途 |
| `unfreeze` | `frz >= amount` | `frz -= amount` | 订单取消 |
| `confirm` | `frz >= amount` | `frz -= amount` **且** `occ += amount`（单语句换位） | 预占确认 |
| `adjustTotal` | 降额方向：`total + delta >= occ + frz` | `total += delta` | 提额/降额/补货 |

**异常定责**（引擎在条件不中时重查账面精确定位）：
- `QuotaExceededException`（带 available）——额度不足是业务常态，拒绝即不变式正确工作
- `QuotaStateException`——释放/解冻/转销超计数或降额越界，是**业务侧记账错乱的信号**（重复释放/漏记占用），防御性拒绝不扣负
- `QuotaNotFoundException`——先 init；建户总量是业务决策，组件不猜

## 4. 存储（D14 布局：store/ 契约层 + 四实现子包）

`store/QuotaStore`（SPI）+ `store/QuotaSql`（方言）；实现：`store.jdbc`（条件 UPDATE）、
`store.memory`（compute 原子块）、`store.redis`（七段 Lua，`store.redis` 脚本外置
`sql/quota-*.lua`）、`store.mybatis`。**唯一硬契约：五操作必须存储端原子**（条件判定与
计数更新同一语句）——"先读账面再写"的两步实现一律不合格。

表 `quota_account`（自动建表+结构校验）：id（sequence 发号）/ quota_key（uk 点查锚）/
total_amount / occupied / frozen / gmt 两列——全部操作 uk 点查命中，无扫描。

## 5. 配置（starsite.quota.*）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关 |
| `store` | auto | auto / memory / jdbc / mybatis / redis（redis 显式指定 + StringRedisTemplate，缺失启动期快速失败） |
| `table-name` | quota_account | 配额表名 |
| `redis-key-prefix` | starsite:quota: | Redis 键前缀 |

## 6. 测试矩阵（35 项 + 真库 IT 5 项）

| 断言 | 覆盖 |
|---|---|
| 五操作语义/不变式边界/共享池/State 防御/NotFound/降额保护/参数校验 | QuotaEngineTest ×12 |
| **并发不变式**：16 线程争 1000 恰 10 成功 6 拒，终态 occupied=1000 | QuotaEngineTest |
| 并发混合（占用×冻结×释放 120 任务）后不变式恒成立 | QuotaEngineTest |
| 方言语句键对齐/建表契约/占位计数 | QuotaSqlMySqlTest ×5（含四载体条件对齐） |
| MyBatis H2 全链路（建户幂等/五操作/降额）/白名单/陈旧结构 fail-fast | MyBatisQuotaStoreTest ×4 |
| Redis：脚本契约/三态翻译/hash 解析/观测降级/前缀缺省 | RedisQuotaStoreTest ×6 |
| 装配：memory 默认/端到端冒烟/redis 装配与快速失败/让位/健康降级 | QuotaAutoConfigurationTest ×6 |
| 真库：DDL/uk 幂等/行级锁下五操作/白名单/陈旧 fail-fast | JdbcQuotaStoreIT（-Pit） |

## 7. 演进路径

- **v0.2 议题**：账户级元数据列（业务透传字段）；配额流水（可选审计表）；带过期占用的
  自动回补（TTL 占用——延迟释放）
- **何时不用**：需要复杂树形分账/会计分录——那是领域件（credit-limit/ledger），业务侧
  在本组件之上自建
