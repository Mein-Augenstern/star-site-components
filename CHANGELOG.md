# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
