# Performance Benchmark Report

> Environment: JDK 21.0.13 | H2 in-memory (MODE=MySQL) + memory stores | 16 cores
> Date: 2026-11-14
> Duration per scenario: fixed iterations (see notes)

## sequence

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| S1: single-thread baseline | 1 | 2,940,410 | 82ns | 89ns | 115ns | 196ns | 482.6μs | standalone mode |
| S2: 1-thread contention | 1 | 2,317,581 | 78ns | 107ns | 112ns | 151ns | 449.0μs | standalone mode |
| S2: 4-thread contention | 4 | 1,996,207 | 182ns | 753ns | 16.0μs | 34.2μs | 214.0μs | standalone mode |
| S2: 8-thread contention | 8 | 1,944,331 | 81ns | 28.6μs | 39.0μs | 77.1μs | 75.76ms | standalone mode |
| S2: 16-thread contention | 16 | 2,425,056 | 80ns | 52.0μs | 82.7μs | 149.6μs | 25.10ms | standalone mode |
| S2: 32-thread contention | 32 | 2,175,545 | 80ns | 105.2μs | 173.4μs | 285.7μs | 37.78ms | standalone mode |

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
| L1: uncontended acquire/release | 1 | 7,621 | 61.5μs | 112.4μs | 189.7μs | 336.1μs | 1.02ms | H2 in-memory, acquire latency (release: 55μs P50) |
| L2: 8-thread contention | 8 | 5,150 | 107.7μs | 613.4μs | 931.8μs | 3.93ms | 3.93ms | fairness σ=26.3 (mean=37.6) |
| L2: 16-thread contention | 16 | 2,158 | 287.7μs | 2.16ms | 2.23ms | 2.23ms | 2.23ms | fairness σ=2.5 (mean=4.0) |
| L2: 32-thread contention | 32 | 1,141 | 165.2μs | 1.67ms | 1.93ms | 1.93ms | 1.93ms | fairness σ=1.5 (mean=1.1) |
| L3: reentrant nested (2-level) | 2 | 2,329 | 421.4μs | 558.1μs | 783.9μs | 1.30ms | 2.64ms | H2 in-memory, full cycle latency |

### Validations

- ✅ all 10000 acquisitions succeeded
- ✅ total wins=301/1000
- ✅ unfair: σ=26.3
- ✅ total wins=64/1000
- ✅ unfair: σ=2.5
- ✅ total wins=34/1000
- ✅ unfair: σ=1.5
- ✅ reentrant semantics: 1000/1000 correct

## async-command

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| A1: memory submit throughput | 1 | 591,668 | 1.1μs | 3.2μs | 3.7μs | 16.6μs | 3.34ms | InMemory store, no DB |
| A2: end-to-end (submit→execute) | 1 | 72,531 | 610.8μs | 811.0μs | 973.8μs | 3.54ms | 13.79ms | H2 + MyBatis, manual tick |
| A3: 2-instance claim fairness | 2 | 1,000 | 0ns | 0ns | 0ns | 0ns | 0ns | A=500 B=500 σ=0.0 |
| A4: memory full chain (submit+claim+execute) | 1 | 4,851 | 202.6μs | 338.4μs | 485.4μs | 855.0μs | 1.43ms | InMemory store, in-process full chain |
| A4: memory full chain (submit+claim+execute) | 16 | 4,156 | 2.33ms | 12.42ms | 18.78ms | 28.02ms | 30.29ms | InMemory store, in-process full chain |

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
| SC1: window-hit single-thread | 1 | 1,091,419 | 693ns | 2.2μs | 2.7μs | 12.1μs | 2.59ms | cron 0 0 8 * * ? ~ 0 0 18 * * ? @Asia/Shanghai, 2 nextAfter/op |
| SC2: window-hit 16-thread | 16 | 3,297,560 | 910ns | 1.7μs | 3.4μs | 24.8μs | 2.93ms | cron 0 0 8 * * ? ~ 0 0 18 * * ? @Asia/Shanghai, 2 nextAfter/op |

### Validations

- ✅ window semantics: 420000/1008000 hits (expected 420000)
- ✅ window semantics: 420000/1008000 hits (expected 420000)

## idempotent

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| I1: first-exec (claim+execute+complete) | 1 | 440,351 | 2.3μs | 2.8μs | 3.4μs | 15.9μs | 8.81ms | Memory store, fresh key per op |
| I1: first-exec (claim+execute+complete) | 16 | 1,273,477 | 2.6μs | 3.9μs | 11.1μs | 55.3μs | 13.66ms | Memory store, fresh key per op |
| I2: replay (DONE hit, zero action) | 1 | 650,733 | 1.5μs | 2.0μs | 2.7μs | 8.8μs | 535.0μs | Memory store, pre-executed keys |
| I2: replay (DONE hit, zero action) | 16 | 1,778,745 | 2.1μs | 3.1μs | 5.5μs | 18.2μs | 157.4μs | Memory store, pre-executed keys |
| I3: exactly-once race (same key) | 16 | 361 | 7.8μs | 340.2μs | 108.20ms | 109.51ms | 109.51ms | wait-poll 100ms dominates replay tail |

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
| Q1: occupy/release round-trip | 1 | 3,547,515 | 144ns | 797ns | 852ns | 1.3μs | 53.5μs | Memory store, same account, amount=1 |
| Q1: occupy/release round-trip | 16 | 2,131,413 | 997ns | 18.7μs | 165.9μs | 238.7μs | 514.8μs | Memory store, same account, amount=1 |

### Validations

- ✅ invariant restored: available=100000 occupied=0 frozen=0
- ✅ invariant restored: available=100000 occupied=0 frozen=0

## tuner

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| T1: applyAll steady-state | 1 | 51,277 | 17.1μs | 33.8μs | 52.1μs | 95.9μs | 1.99ms | 4 pools, daily-hit window, no-op skip |
| T1: applyAll steady-state | 16 | 184,630 | 22.8μs | 397.1μs | 1.43ms | 2.60ms | 3.95ms | 4 pools, daily-hit window, no-op skip |

### Validations

- ✅ steady-state params stable (core/max/keep unchanged)
- ✅ no spurious applies: audits 4 -> 4
- ✅ steady-state params stable (core/max/keep unchanged)
- ✅ no spurious applies: audits 4 -> 4

