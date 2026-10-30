# sequence · 雪花 ID

> [← 返回概览](../README.md)

## 单机（默认）

```yaml
starsite:
  sequence:
    mode: standalone   # 默认
    worker-id: 1       # 多实例部署时各不相同即可
```

## 分布式（MySQL 原子租约）

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

## 使用

```java
@RequiredArgsConstructor
class OrderService {
    private final StarsiteIdGenerator idGenerator;   // 注入即用

    void createOrder() {
        order.setId(idGenerator.next());             // 或 nextStr() 字符串形态
    }
}
```

## 时钟回拨（雪花系命门）

回拨发生时把虚拟时间冻结在上次发号毫秒，**借序列位余量继续发号**，等物理时钟追平——id 时间戳字段绝不倒退：

- `backward-policy: CLAMP`（默认）：任何幅度钳制（大回拨告警一次）
- `backward-policy: FAIL`：回拨超过 `max-backward-millis`（默认 2000ms）抛 `ClockBackwardException` 拒绝发号，宁可短暂不可用绝不发倒退 id

## 全量配置（前缀 starsite.sequence）

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

## 健康端点

引 actuator 即有 `starsiteSequence`：形态/机器位（被动上报，不消耗号段）。
