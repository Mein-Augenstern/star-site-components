# Performance Benchmark Report

> Environment: JDK 21.0.13 | H2 in-memory (MODE=MySQL) + memory stores | 16 cores
> Date: 2026-11-14
> Duration per scenario: fixed iterations (see notes)

> **复现方式**（本文件由 BenchRunner 全量运行自动重写，落盘于运行目录）：
>
> ```bash
> # 全量（七组件，约 1~2 分钟）
> JAVA_HOME=~/develop/jdk21/Contents/Home mvn -pl bench -Pbench clean compile \
>     exec:java -Dexec.mainClass=com.starsite.bench.BenchRunner
>
> # 单组件（sequence | lock | async | schedule | idempotent | quota | tuner）
> # ... -Dexec.args="idempotent"
> ```
>
> **七组件基准路径**：
>
> | 组件 | 场景 | 形态 |
> |---|---|---|
> | sequence | S1/S2 `next()` 发号 | standalone 纯内存（雪花算法吞吐 + 唯一性） |
> | lock | L1/L2 `tryLock`+`unlock` 往返 | H2 内存库（MySQL 兼容，争锁/释放往返） |
> | async-command | A1/A4 submit → 认领 → 执行 | memory 形态（in-process 全链路，A2/A3 为 H2 对照） |
> | schedule | SC1/SC2 窗口命中判定 | 纯时间函数（`startCron`/`endCron` 各一次 `nextAfter` 求值） |
> | idempotent | I1 首次执行 / I2 回放 / I3 同键恰一竞争 | memory 形态 |
> | quota | Q1 `occupy`+`release` 往返 | memory 形态（不变式条件 UPDATE 等价物） |
> | tuner | T1 `applyAll()` 稳态求值 | memory 形态 + 已注册池（窗口求值 + 条件应用跳过） |

## sequence

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| S1: single-thread baseline | 1 | 2,057,719 | 103ns | 113ns | 165ns | 410ns | 394.2μs | standalone mode |
| S2: 1-thread contention | 1 | 1,578,090 | 103ns | 128ns | 172ns | 397ns | 92.0μs | standalone mode |
| S2: 4-thread contention | 4 | 1,445,561 | 122ns | 10.7μs | 19.5μs | 41.1μs | 2.42ms | standalone mode |
| S2: 8-thread contention | 8 | 1,682,665 | 103ns | 18.5μs | 48.7μs | 95.0μs | 110.23ms | standalone mode |
| S2: 16-thread contention | 16 | 1,744,519 | 81ns | 69.9μs | 88.4μs | 130.8μs | 49.05ms | standalone mode |
| S2: 32-thread contention | 32 | 1,838,151 | 83ns | 386ns | 223.2μs | 534.5μs | 3.46ms | standalone mode |

### Validations

- ✅ uniqueness: 1000000/1000000 (100%)
- ✅ no ID collision across 1 threads
- ✅ uniqueness: 1000000/1000000 (100%)
- ✅ no ID collision across 1 threads
- ✅ uniqueness: 1000000/1000000 (100%)
- ✅ no ID collision across 4 threads
- ✅ uniqueness: 1000000/1000000 (100%)
- ✅ no ID collision across 8 threads
- ✅ uniqueness: 1000000/1000000 (100%)
- ✅ no ID collision across 16 threads
- ✅ uniqueness: 1000000/1000000 (100%)
- ✅ no ID collision across 32 threads

## lock

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| L1: uncontended acquire/release | 1 | 8,142 | 48.8μs | 128.6μs | 229.0μs | 927.7μs | 22.18ms | H2 in-memory, acquire latency (release: 44μs P50) |
| L2: 8-thread contention | 8 | 5,125 | 103.4μs | 528.9μs | 2.75ms | 16.06ms | 16.06ms | fairness σ=22.6 (mean=35.0) |
| L2: 16-thread contention | 16 | 2,294 | 182.0μs | 756.7μs | 1.76ms | 1.76ms | 1.76ms | fairness σ=5.6 (mean=4.3) |
| L2: 32-thread contention | 32 | 1,156 | 147.8μs | 801.3μs | 1.83ms | 1.83ms | 1.83ms | fairness σ=2.6 (mean=1.0) |
| L3: reentrant nested (2-level) | 2 | 2,707 | 352.8μs | 503.0μs | 648.7μs | 2.00ms | 2.35ms | H2 in-memory, full cycle latency |

### Validations

- ✅ all 10000 acquisitions succeeded
- ✅ total wins=280/1000
- ✅ unfair: σ=22.6
- ✅ total wins=68/1000
- ✅ unfair: σ=5.6
- ✅ total wins=32/1000
- ✅ unfair: σ=2.6
- ✅ reentrant semantics: 1000/1000 correct

## async-command

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| A1: memory submit throughput | 1 | 730,840 | 1.0μs | 1.3μs | 1.5μs | 25.6μs | 3.45ms | InMemory store, no DB |
| A2: end-to-end (submit→execute) | 1 | 84,830 | 512.3μs | 809.5μs | 1.15ms | 3.00ms | 11.79ms | H2 + MyBatis, manual tick |
| A3: 2-instance claim fairness | 2 | 1,000 | 0ns | 0ns | 0ns | 0ns | 0ns | A=500 B=500 σ=0.0 |
| A4: memory full chain (submit+claim+execute) | 1 | 5,447 | 172.2μs | 286.5μs | 393.7μs | 526.8μs | 705.0μs | InMemory store, in-process full chain |
| A4: memory full chain (submit+claim+execute) | 16 | 5,010 | 2.15ms | 9.12ms | 13.24ms | 23.94ms | 29.11ms | InMemory store, in-process full chain |

### Validations

- ✅ all 50000 submissions accepted
- ✅ executed 1000/1000 commands
- ✅ claimed 1000/1000
- ✅ no overlap between instances
- ✅ fair split: σ=0.0
- ✅ executed 5000/5000 commands
- ✅ backlog drained: 0 unfinished
- ✅ executed 4992/4992 commands
- ✅ backlog drained: 0 unfinished

## schedule

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| SC1: window-hit single-thread | 1 | 1,608,191 | 504ns | 724ns | 1.8μs | 16.4μs | 1.91ms | cron 0 0 8 * * ? ~ 0 0 18 * * ? @Asia/Shanghai, 2 nextAfter/op |
| SC2: window-hit 16-thread | 16 | 3,356,922 | 738ns | 1.3μs | 2.2μs | 23.4μs | 2.55ms | cron 0 0 8 * * ? ~ 0 0 18 * * ? @Asia/Shanghai, 2 nextAfter/op |

### Validations

- ✅ window semantics: 420000/1008000 hits (expected 420000)
- ✅ window semantics: 420000/1008000 hits (expected 420000)

## idempotent

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| I1: first-exec (claim+execute+complete) | 1 | 455,135 | 1.7μs | 2.5μs | 3.6μs | 18.9μs | 10.63ms | Memory store, fresh key per op |
| I1: first-exec (claim+execute+complete) | 16 | 1,460,061 | 2.2μs | 3.6μs | 7.5μs | 105.9μs | 8.17ms | Memory store, fresh key per op |
| I2: replay (DONE hit, zero action) | 1 | 631,600 | 1.1μs | 2.0μs | 3.9μs | 13.4μs | 13.69ms | Memory store, pre-executed keys |
| I2: replay (DONE hit, zero action) | 16 | 1,926,982 | 1.9μs | 2.7μs | 4.7μs | 17.5μs | 83.6μs | Memory store, pre-executed keys |
| I3: exactly-once race (same key) | 16 | 559 | 8.4μs | 122.0μs | 103.81ms | 105.25ms | 105.25ms | wait-poll 100ms dominates replay tail |

### Validations

- ✅ exactly-executed 100000/100000
- ✅ exactly-executed 100000/100000
- ✅ replayed 100000/100000
- ✅ action zero-execution on replay path
- ✅ replayed 100000/100000
- ✅ action zero-execution on replay path
- ✅ exactly-once: 1 EXECUTED x 50 rounds
- ✅ action ran exactly once per key: 50/50
- ✅ zero conflicts (wait window sufficed)
- ✅ others replayed: 750

## quota

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| Q1: occupy/release round-trip | 1 | 3,881,705 | 117ns | 853ns | 1.7μs | 3.3μs | 1.86ms | Memory store, same account, amount=1 |
| Q1: occupy/release round-trip | 16 | 2,115,026 | 907ns | 12.3μs | 172.9μs | 282.9μs | 2.34ms | Memory store, same account, amount=1 |

### Validations

- ✅ invariant restored: available=100000 occupied=0 frozen=0
- ✅ invariant restored: available=100000 occupied=0 frozen=0

## tuner

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| T1: applyAll steady-state | 1 | 50,778 | 14.6μs | 36.5μs | 70.0μs | 130.8μs | 2.19ms | 4 pools, daily-hit window, no-op skip |
| T1: applyAll steady-state | 16 | 163,526 | 22.6μs | 401.7μs | 1.75ms | 2.98ms | 4.89ms | 4 pools, daily-hit window, no-op skip |

### Validations

- ✅ steady-state params stable (core/max/keep unchanged)
- ✅ no spurious applies: audits 4 -> 4
- ✅ steady-state params stable (core/max/keep unchanged)
- ✅ no spurious applies: audits 4 -> 4
