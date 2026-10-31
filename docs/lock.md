# lock · 分布式锁

> [← 返回概览](../README.md)

多实例互斥的标准件：DB 唯一键形态（无 Redis 依赖）与 Redis 形态（SETNX + TTL + Lua 释放）一套 API，
async-command 的 LEADER 选主也走它。

## 配置与使用

```yaml
starsite:
  lock:
    mode: mysql              # 默认；需 MyBatis 环境 + DataSource + sequence starter（锁行主键发号）
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

## 语义清单

- **DB 形态**：`lock_name` 唯一键单行 UPDATE 原子抢占（空/过期/自己持锁三种命中）；种子播种有实例内记忆（性能提示而非正确性依据）——未抢到时校验锁行存活性，外部删行自动重播种重试自愈，无需重启
- **限时等待获取**：`tryLock(name, waitMillis)`——立即尝试 + 窗口内轮询重试（100ms 间隔；无公平性，DB fail-open 逐次生效；中断恢复标记返回 false）
- **可重入（ownerId 粒度，存储端计数）**：持有层数存于锁表 hold_count 列（DB）/Redis hash（field=ownerId）——同 ownerId 跨实例共享同一份计数，嵌套作用域的内层 unlock 只递减，归零才真正释放
- **SQL 全索引命中**：锁表仅 PK(id)+uk(lock_name) 最小索引集（争锁/释放均为单条原子 UPDATE，含重入计数递增/递减），争锁/释放/查询全部 lock_name 点查，无扫描无慢 SQL（索引完备性有单测断言）
- **Redis 形态**：`SET key value NX PX ttl` + 校验 value 的 Lua 原子释放（防误删他方锁）
- **fail-open**：锁存储异常时放行（宁可多实例竞争不停摆，上层幂等兜底），WARN 可见

## 全量配置（前缀 starsite.lock）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关 |
| `mode` | mysql | mysql / redis |
| `table-name` | starsite_lock | db 锁表（通用互斥，不绑定调度场景） |
| `ttl-ms` | 30000 | 持锁 TTL |
| `owner-id` | 应用名@pid | 持锁者标识 |
| `redis-key-prefix` | starsite:lock: | redis 键前缀 |
| `init-ddl` | true | db 形态自动建表 |
