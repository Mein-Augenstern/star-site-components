# Performance Benchmark Report

> Environment: JDK 21.0.7 | H2 in-memory (MODE=MySQL) | 16 cores
> Date: 2026-10-31
> Duration per scenario: fixed iterations (see notes)

## sequence

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| S1: single-thread baseline | 1 | 1,877,664 | 99ns | 118ns | 170ns | 535ns | 417.3μs | standalone mode |
| S2: 1-thread contention | 1 | 1,666,644 | 104ns | 139ns | 493ns | 751ns | 290.8μs | standalone mode |
| S2: 4-thread contention | 4 | 1,368,208 | 138ns | 789ns | 21.6μs | 59.3μs | 81.92ms | standalone mode |
| S2: 8-thread contention | 8 | 1,329,480 | 89ns | 31.9μs | 42.3μs | 73.2μs | 82.31ms | standalone mode |
| S2: 16-thread contention | 16 | 1,554,257 | 96ns | 4.3μs | 101.7μs | 166.4μs | 746.0μs | standalone mode |
| S2: 32-thread contention | 32 | 1,531,236 | 97ns | 395ns | 230.1μs | 612.9μs | 76.48ms | standalone mode |

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
| L1: uncontended acquire/release | 1 | 5,689 | 69.2μs | 163.6μs | 345.1μs | 1.24ms | 175.90ms | H2 in-memory, acquire latency (release: 62μs P50) |
| L2: 8-thread contention | 8 | 4,197 | 126.1μs | 629.8μs | 1.48ms | 36.54ms | 36.54ms | fairness σ=30.3 (mean=45.1) |
| L2: 32-thread contention | 32 | 942 | 283.1μs | 10.80ms | 19.27ms | 19.27ms | 19.27ms | fairness σ=1.3 (mean=1.5) |
| L3: reentrant nested (2-level) | 2 | 1,481 | 608.3μs | 981.5μs | 1.72ms | 4.26ms | 5.56ms | H2 in-memory, full cycle latency |

### Validations

- ✅ all 10000 acquisitions succeeded
- ✅ total wins=361/1000
- ✅ unfair: σ=30.3
- ✅ total wins=47/1000
- ✅ unfair: σ=1.3
- ✅ reentrant semantics: 1000/1000 correct

## async-command

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| A1: memory submit throughput | 1 | 373,291 | 1.6μs | 4.6μs | 8.2μs | 32.0μs | 6.55ms | InMemory store, no DB |
| A2: end-to-end (submit→execute) | 1 | 21,659 | 1.21ms | 2.83ms | 3.49ms | 8.74ms | 46.17ms | H2 + MyBatis, manual tick |

### Validations

- ✅ all 50000 submissions accepted
- ✅ executed 1000/1000 commands

