# Performance Benchmark Report

> Environment: JDK 21.0.13 | H2 in-memory (MODE=MySQL) + memory stores | 16 cores
> Date: 2026-11-14
> Duration per scenario: fixed iterations (see notes)

## sequence

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| S1: single-thread baseline | 1 | 2,676,889 | 84ns | 105ns | 111ns | 141ns | 388.6μs | standalone mode |
| S2: 1-thread contention | 1 | 2,114,841 | 95ns | 135ns | 142ns | 165ns | 382.2μs | standalone mode |
| S2: 4-thread contention | 4 | 1,712,139 | 183ns | 7.5μs | 16.1μs | 22.9μs | 125.8μs | standalone mode |
| S2: 8-thread contention | 8 | 2,159,320 | 111ns | 1.8μs | 36.6μs | 64.4μs | 220.4μs | standalone mode |
| S2: 16-thread contention | 16 | 2,207,133 | 86ns | 60.3μs | 87.6μs | 131.8μs | 27.75ms | standalone mode |
| S2: 32-thread contention | 32 | 1,925,252 | 84ns | 437ns | 194.4μs | 268.0μs | 23.77ms | standalone mode |

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
| L1: uncontended acquire/release | 1 | 7,399 | 60.4μs | 106.4μs | 213.6μs | 321.8μs | 47.38ms | H2 in-memory, acquire latency (release: 54μs P50) |
| L2: 8-thread contention | 8 | 5,979 | 89.9μs | 544.8μs | 756.2μs | 3.60ms | 3.60ms | fairness σ=20.8 (mean=39.1) |
| L2: 16-thread contention | 16 | 2,397 | 146.4μs | 1.23ms | 1.96ms | 1.96ms | 1.96ms | fairness σ=2.5 (mean=3.5) |
| L2: 32-thread contention | 32 | 1,552 | 300.8μs | 3.18ms | 10.22ms | 10.22ms | 10.22ms | fairness σ=2.0 (mean=1.5) |
| L3: reentrant nested (2-level) | 2 | 2,663 | 389.4μs | 451.6μs | 510.2μs | 772.6μs | 2.02ms | H2 in-memory, full cycle latency |

### Validations

- ✅ all 10000 acquisitions succeeded
- ✅ total wins=313/1000
- ✅ unfair: σ=20.8
- ✅ total wins=56/1000
- ✅ unfair: σ=2.5
- ✅ total wins=47/1000
- ✅ unfair: σ=2.0
- ✅ reentrant semantics: 1000/1000 correct

## async-command

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| A1: memory submit throughput | 1 | 709,932 | 1.3μs | 1.5μs | 1.8μs | 5.9μs | 2.51ms | InMemory store, no DB |
| A2: end-to-end (submit→execute) | 1 | 52,844 | 582.1μs | 759.3μs | 827.4μs | 11.36ms | 18.92ms | H2 + MyBatis, manual tick |
| A4: memory full chain (submit+claim+execute) | 1 | 257 | 2.59ms | 9.65ms | 10.63ms | 12.78ms | 53.35ms | InMemory store, in-process full chain |
| A4: memory full chain (submit+claim+execute) | 16 | 226 | 5.53ms | 403.44ms | 981.99ms | 1.71s | 3.84s | InMemory store, in-process full chain |

### Validations

- ✅ all 50000 submissions accepted
- ✅ executed 1000/1000 commands
- ✅ executed 50000/50000 commands
- ✅ backlog drained: 0 unfinished
- ✅ executed 50000/50000 commands
- ✅ backlog drained: 0 unfinished

## schedule

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| SC1: window-hit single-thread | 1 | 770,474 | 968ns | 3.4μs | 4.0μs | 16.5μs | 1.81ms | cron 0 0 8 * * ? ~ 0 0 18 * * ? @Asia/Shanghai, 2 nextAfter/op |
| SC2: window-hit 16-thread | 16 | 2,927,971 | 1.0μs | 2.0μs | 7.4μs | 39.7μs | 1.61ms | cron 0 0 8 * * ? ~ 0 0 18 * * ? @Asia/Shanghai, 2 nextAfter/op |

### Validations

- ✅ window semantics: 420000/1008000 hits (expected 420000)
- ✅ window semantics: 420000/1008000 hits (expected 420000)

## idempotent

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| I1: first-exec (claim+execute+complete) | 1 | 281,626 | 3.3μs | 4.3μs | 6.4μs | 32.3μs | 12.72ms | Memory store, fresh key per op |
| I1: first-exec (claim+execute+complete) | 16 | 748,063 | 8.6μs | 16.1μs | 68.5μs | 580.1μs | 15.29ms | Memory store, fresh key per op |
| I2: replay (DONE hit, zero action) | 1 | 437,332 | 2.2μs | 3.0μs | 4.1μs | 18.7μs | 726.5μs | Memory store, pre-executed keys |
| I2: replay (DONE hit, zero action) | 16 | 1,547,267 | 2.5μs | 3.5μs | 5.9μs | 23.0μs | 579.6μs | Memory store, pre-executed keys |
| I3: exactly-once race (same key) | 16 | 517 | 10.5μs | 569.9μs | 104.40ms | 105.12ms | 105.12ms | wait-poll 100ms dominates replay tail |

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
| Q1: occupy/release round-trip | 1 | 2,120,245 | 202ns | 1.1μs | 1.3μs | 2.2μs | 18.20ms | Memory store, same account, amount=1 |
| Q1: occupy/release round-trip | 16 | 1,906,314 | 1.0μs | 14.3μs | 189.8μs | 283.9μs | 1.83ms | Memory store, same account, amount=1 |

### Validations

- ✅ invariant restored: available=100000 occupied=0 frozen=0
- ✅ invariant restored: available=100000 occupied=0 frozen=0

## tuner

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| T1: applyAll steady-state | 1 | 36,479 | 24.6μs | 46.2μs | 57.8μs | 111.2μs | 2.08ms | 4 pools, daily-hit window, no-op skip |
| T1: applyAll steady-state | 16 | 169,100 | 23.7μs | 492.8μs | 1.30ms | 2.12ms | 3.40ms | 4 pools, daily-hit window, no-op skip |

### Validations

- ✅ steady-state params stable (core/max/keep unchanged)
- ✅ no spurious applies: audits 4 -> 4
- ✅ steady-state params stable (core/max/keep unchanged)
- ✅ no spurious applies: audits 4 -> 4

