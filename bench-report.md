# Performance Benchmark Report

> Environment: JDK 21.0.13 | H2 in-memory (MODE=MySQL) + memory stores | 16 cores
> Date: 2026-11-14
> Duration per scenario: fixed iterations (see notes)

## sequence

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| S1: single-thread baseline | 1 | 2,871,022 | 79ns | 86ns | 103ns | 161ns | 4.61ms | standalone mode |
| S2: 1-thread contention | 1 | 2,111,944 | 78ns | 115ns | 120ns | 143ns | 423.4μs | standalone mode |
| S2: 4-thread contention | 4 | 2,036,837 | 101ns | 890ns | 20.9μs | 48.7μs | 11.02ms | standalone mode |
| S2: 8-thread contention | 8 | 2,101,903 | 81ns | 18.8μs | 47.3μs | 97.0μs | 61.18ms | standalone mode |
| S2: 16-thread contention | 16 | 2,014,977 | 79ns | 57.0μs | 78.8μs | 165.9μs | 40.34ms | standalone mode |
| S2: 32-thread contention | 32 | 2,071,389 | 81ns | 402ns | 187.4μs | 323.3μs | 663.6μs | standalone mode |

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
| L1: uncontended acquire/release | 1 | 7,234 | 64.5μs | 116.7μs | 181.0μs | 311.0μs | 866.3μs | H2 in-memory, acquire latency (release: 58μs P50) |
| L2: 8-thread contention | 8 | 3,563 | 151.4μs | 720.4μs | 1.66ms | 6.04ms | 6.04ms | fairness σ=19.1 (mean=34.1) |
| L2: 16-thread contention | 16 | 1,954 | 203.9μs | 1.36ms | 2.30ms | 2.30ms | 2.30ms | fairness σ=2.3 (mean=4.3) |
| L2: 32-thread contention | 32 | 1,288 | 186.5μs | 3.47ms | 9.31ms | 9.31ms | 9.31ms | fairness σ=1.7 (mean=1.2) |
| L3: reentrant nested (2-level) | 2 | 2,760 | 337.9μs | 527.4μs | 738.8μs | 1.89ms | 3.72ms | H2 in-memory, full cycle latency |

### Validations

- ✅ all 10000 acquisitions succeeded
- ✅ total wins=273/1000
- ✅ unfair: σ=19.1
- ✅ total wins=68/1000
- ✅ unfair: σ=2.3
- ✅ total wins=37/1000
- ✅ unfair: σ=1.7
- ✅ reentrant semantics: 1000/1000 correct

## async-command

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| A1: memory submit throughput | 1 | 618,834 | 1.3μs | 1.6μs | 2.5μs | 29.9μs | 2.71ms | InMemory store, no DB |
| A2: end-to-end (submit→execute) | 1 | 66,415 | 537.1μs | 742.4μs | 902.7μs | 2.58ms | 15.06ms | H2 + MyBatis, manual tick |
| A3: 2-instance claim fairness | 2 | 1,000 | 0ns | 0ns | 0ns | 0ns | 0ns | A=500 B=500 σ=0.0 |
| A4: memory full chain (submit+claim+execute) | 1 | 4,852 | 197.1μs | 330.2μs | 466.9μs | 636.4μs | 836.1μs | InMemory store, in-process full chain |
| A4: memory full chain (submit+claim+execute) | 16 | 4,612 | 1.85ms | 10.63ms | 13.37ms | 21.82ms | 25.51ms | InMemory store, in-process full chain |

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
| SC1: window-hit single-thread | 1 | 1,182,020 | 595ns | 2.2μs | 2.7μs | 19.7μs | 22.33ms | cron 0 0 8 * * ? ~ 0 0 18 * * ? @Asia/Shanghai, 2 nextAfter/op |
| SC2: window-hit 16-thread | 16 | 3,205,154 | 862ns | 1.5μs | 2.8μs | 23.5μs | 17.29ms | cron 0 0 8 * * ? ~ 0 0 18 * * ? @Asia/Shanghai, 2 nextAfter/op |

### Validations

- ✅ window semantics: 420000/1008000 hits (expected 420000)
- ✅ window semantics: 420000/1008000 hits (expected 420000)

## idempotent

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| I1: first-exec (claim+execute+complete) | 1 | 357,047 | 2.2μs | 2.7μs | 4.1μs | 26.2μs | 30.21ms | Memory store, fresh key per op |
| I1: first-exec (claim+execute+complete) | 16 | 1,310,067 | 2.4μs | 4.3μs | 21.3μs | 92.1μs | 13.85ms | Memory store, fresh key per op |
| I2: replay (DONE hit, zero action) | 1 | 499,029 | 1.8μs | 2.4μs | 3.0μs | 8.4μs | 534.0μs | Memory store, pre-executed keys |
| I2: replay (DONE hit, zero action) | 16 | 1,538,613 | 2.9μs | 4.4μs | 9.4μs | 19.8μs | 169.1μs | Memory store, pre-executed keys |
| I3: exactly-once race (same key) | 16 | 583 | 6.1μs | 127.7μs | 107.41ms | 109.38ms | 109.38ms | wait-poll 100ms dominates replay tail |

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
| Q1: occupy/release round-trip | 1 | 3,927,821 | 114ns | 789ns | 1.3μs | 1.6μs | 62.3μs | Memory store, same account, amount=1 |
| Q1: occupy/release round-trip | 16 | 2,176,315 | 929ns | 15.8μs | 163.6μs | 234.4μs | 639.5μs | Memory store, same account, amount=1 |

### Validations

- ✅ invariant restored: available=100000 occupied=0 frozen=0
- ✅ invariant restored: available=100000 occupied=0 frozen=0

## tuner

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| T1: applyAll steady-state | 1 | 51,771 | 15.7μs | 36.1μs | 66.1μs | 120.8μs | 2.58ms | 4 pools, daily-hit window, no-op skip |
| T1: applyAll steady-state | 16 | 165,836 | 22.9μs | 448.3μs | 1.61ms | 2.75ms | 4.80ms | 4 pools, daily-hit window, no-op skip |

### Validations

- ✅ steady-state params stable (core/max/keep unchanged)
- ✅ no spurious applies: audits 4 -> 4
- ✅ steady-state params stable (core/max/keep unchanged)
- ✅ no spurious applies: audits 4 -> 4

