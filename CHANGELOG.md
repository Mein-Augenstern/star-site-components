# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added (0.2.0)
- Lock: watchdog auto-renewal (`WatchdogLockClient` decorator + `starsite.lock.watchdog-enabled=true`)
  — automatically renews lock TTL every TTL/3 for long-running tasks
- Sequence: Redis workerId lease (`RedisWorkerIdAllocator` + `starsite.sequence.mode=redis`)
  — atomic INCR allocation, sub-millisecond, for teams with Redis but no MySQL
- Lock: admin client (forceRelease / findHolder / listHeldLocks / getLockInfo)

### Changed (0.2.0)
- BREAKING: config key unification — `store: jdbc|memory` → `mode: mysql|standalone` (async-command);
  `mode: db` → `mode: mysql` (lock); three components now share unified vocabulary `mode=standalone|mysql|redis`
- JDK target lowered from 21 to 17 (Spring Boot 3 minimum, broader compatibility)
- sequence starter changed from `provided` to `compile` scope (single-component users no longer hit missing StarsiteIdGenerator)

### Fixed (0.2.0)
- MyBatisMysqlWorkerIdAllocator: retry null → throw exception (was silently defaulting workerId=1)
- MyBatisAsyncCommandStore.reclaimStale: hardcoded clock → injected TimeSource (parity with JdbcStore)
- DemoController: Map.of null name → NPE (normalized upfront)
- Multiple stale javadoc fixes (.properties→.xml, GeneratedKeyHolder residue, dangling @link)

### Added
- Sequence: snowflake ID generator with clock-backward clamping (CLAMP/FAIL)
- Sequence: MySQL workerId lease (`__global__` pool + `LAST_INSERT_ID` atomic increment)
- Async-command: token-claim scheduling with exponential backoff retry + dead letter
- Async-command: three execution modes (SCHEDULED / ASYNC_POOL / IMMEDIATE)
- Async-command: batch executor support
- Async-command: distributed scheduling modes (COMPETING / LEADER / EXTERNAL)
- Async-command: Micrometer metrics + health indicator + admin endpoints
- Lock: reentrant distributed lock (DB `hold_count` column / Redis hash)
- Lock: timed wait acquisition (`tryLock(name, waitMillis)`)
- Lock: row-loss self-healing
- Lock: admin client (force release / list held locks / diagnostics)
- All: MyBatis adapter (Mapper XML auto-discovery via same-package convention)
- All: SQL externalization (Mapper XML + dialect XML + Lua files)
- All: index completeness unit test assertions
- All: list/detail column separation (payload TEXT excluded from list queries)

### Changed
- Lock table renamed from `component_schedule_lock` to `starsite_lock`
- README restructured: 87-line overview + detailed docs in `docs/`

## [0.1.0] - TBD
