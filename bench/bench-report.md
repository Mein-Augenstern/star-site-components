# Performance Benchmark Report

> Environment: JDK 21.0.7 | H2 in-memory (MODE=MySQL) | 16 cores
> Date: 2026-10-31
> Duration per scenario: fixed iterations (see notes)

## sequence

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| S1: single-thread baseline | 1 | 2,525,452 | 78ns | 87ns | 109ns | 193ns | 3.24ms | standalone mode |
| S2: 1-thread contention | 1 | 2,097,854 | 80ns | 97ns | 101ns | 128ns | 487.9μs | standalone mode |
| S2: 4-thread contention | 4 | 1,856,698 | 98ns | 3.6μs | 17.3μs | 45.0μs | 9.07ms | standalone mode |
| S2: 8-thread contention | 8 | 2,292,566 | 78ns | 21.4μs | 36.3μs | 100.1μs | 62.46ms | standalone mode |
| S2: 16-thread contention | 16 | 2,172,227 | 80ns | 66.6μs | 82.9μs | 135.9μs | 42.70ms | standalone mode |
| S2: 32-thread contention | 32 | 1,957,666 | 82ns | 457ns | 197.9μs | 315.4μs | 81.07ms | standalone mode |

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
| L1: uncontended acquire/release | 1 | 15,777 | 30.7μs | 62.6μs | 97.3μs | 167.9μs | 687.6μs | H2 in-memory, acquire latency (release: 19μs P50) |
| L2: 8-thread contention | 8 | 22,288 | 69.6μs | 545.8μs | 698.4μs | 1.38ms | 1.59ms | fairness σ=0.0 (mean=125.0) |
| L2: 32-thread contention | 32 | 24,097 | 55.1μs | 2.15ms | 2.58ms | 3.37ms | 3.37ms | fairness σ=0.0 (mean=31.0) |
| L3: reentrant nested (2-level) | 2 | 6,352 | 100.3μs | 332.5μs | 445.4μs | 589.3μs | 645.6μs | H2 in-memory, full cycle latency |

### Validations

- ✅ all 10000 acquisitions succeeded
- ✅ total wins=1000/1000
- ✅ fair: σ=0.0
- ✅ total wins=992/1000
- ✅ fair: σ=0.0
- ✅ REENTRANT FAILURE: 0/1000

## async-command

| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |
|---|---|---|---|---|---|---|---|---|
| A1: memory submit throughput | 1 | 119,239 | 5.8μs | 21.4μs | 44.3μs | 207.9μs | 7.20ms | InMemory store, no DB |

### Validations

- ✅ all 50000 submissions accepted

