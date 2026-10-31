# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.1] — 2026-10-31

生产链路压测（Gateway → bench-service → MySQL 实机）产出的修复与增强批次。

### Fixed
- **BUG-008**: starter 的 `AsyncCommandAdminController` 类级 `@RestController` 会被宿主
  `scanBasePackages` 宽扫描直接实例化，绕过 `admin-enabled=false` 条件装配导致启动失败
  ——改为类级 `@ResponseBody`（非 stereotype，不被扫描；MVC 识别/JSON 语义不变）
- **BUG-009**: 存储结构漂移静默失败——全部 5 个建表点（lock、async×2 路径、sequence×2 路径）
  增加启动期结构校验：表存在但缺契约列 → fail-fast 并给出迁移指引（旧结构曾导致
  workerId 静默回落 hash 撞号、调度器每 5s bad SQL grammar 且命令永久积压）
- **BUG-006**: bench L3 可重入 0/1000——双层真因：H2Infra 的 mapper session 被
  try-with-resources 关闭（fail-open 掩盖异常）+ 全场景固定种子 id=1 主键冲突致锁行缺失
- **BUG-007**: bench A2/A3 构造器不匹配——已随 4 参便捷构造器解决，本轮 bench 实测确认

### Changed
- **OBS-101**: async 调度器满批追赶——认领满批时连续认领直到清空或达 8 轮上限
  （追赶吞吐 8×batch/interval）；默认 `batch-size` 10 → 50（750 条积压消化从 ~6 分钟
  降至秒级，生产链路压测实证）
- **OBS-102**: lock 限时等待自适应退避——重试间隔 10ms 起步逐次翻倍、封顶 100ms
  （16 线程争抢下 P95 从 ~3×100ms 轮询量化长尾降一个量级；高争抢节奏与旧版一致）
- **OBS-103**: lock 文档标注 mysql 形态的完整依赖清单（mybatis-spring-boot-starter +
  DataSource + sequence starter + mapper-locations）

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
