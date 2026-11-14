# star-site-components

[![CI](https://github.com/Mein-Augenstern/star-site-components/actions/workflows/ci.yml/badge.svg)](https://github.com/Mein-Augenstern/star-site-components/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2+-brightgreen.svg)](https://spring.io/projects/spring-boot)

**[中文](README.md)**

Lightweight distributed components for Spring Boot 3 — **DB-first, only needs MySQL**.

Six production-proven components that compose together:

| Component | What it does | Single machine | Distributed | Docs |
|---|---|---|---|---|
| **sequence** | Snowflake ID generator (clock-backward safe) | Fixed worker ID | MySQL atomic lease | [docs/sequence.md](docs/sequence.md) |
| **async-command** | Async task queue (retry, dead letter, batch) | In-memory queue | MySQL token claim | [docs/async-command.md](docs/async-command.md) |
| **lock** | Distributed lock (reentrant, timed wait, self-healing) | — | DB unique key / Redis hash | [docs/lock.md](docs/lock.md) |
| **schedule** | Scheduled task exactly-once (annotation + cron + run log) | In-memory observation | Reuses lock claim + run_log | [docs/schedule.md](docs/schedule.md) |
| **idempotent** | API idempotency key (Stripe-style: intercept + **result replay**) | In-memory state machine | MySQL uk atomic claim / Redis Lua + TTL | [docs/idempotent.md](docs/idempotent.md) |
| **quota** | Balance-type quota (5 atomic ops + invariant occupied+frozen≤total) | In-memory atomic block | MySQL conditional UPDATE / Redis Lua | [docs/quota.md](docs/quota.md) |

```
starsite-sequence ──────► starsite-sequence-spring-boot-starter
starsite-async-command ─► starsite-async-command-spring-boot-starter
starsite-lock ──────────► starsite-lock-spring-boot-starter
starsite-schedule ──────► starsite-schedule-spring-boot-starter
starsite-idempotent ────► starsite-idempotent-spring-boot-starter
starsite-quota ─────────► starsite-quota-spring-boot-starter
```

- JDK 17+ · Spring Boot 3.2+ · Zero Lombok in core · Apache-2.0

| JDK | Supported | Note |
|---|---|---|
| 17 | ✅ | Minimum (Spring Boot 3 requirement) |
| 21 | ✅ | Recommended |
| 8/11 | ❌ | Requires Spring Boot 2.x (not supported) |
- **285 unit tests** + Testcontainers MySQL integration tests
- **Internal adoption & extension**: [docs/extension.md](docs/extension.md) — three integration channels (config-swap / dialect Bean / SPI impl) + bean-yield mechanism + per-component extension point inventory
- Production-proven in [star-site-backend](https://github.com/Mein-Augenstern/star-site-backend) (12 microservices, 18 containers)

## Why This Exists

Most distributed lock libraries need Redis. Most task schedulers need a dedicated server. Most ID generators need a separate service. Most idempotency solutions only reject duplicates instead of replaying the first result.

**If you already have MySQL, you're done** — add the starters and get all capabilities with zero additional infrastructure.

| Alternative | Requires | This project |
|---|---|---|
| Redisson (10k★) | Redis cluster | MySQL (already have it) |
| ShedLock (3.5k★) | Redis / JDBC / ZooKeeper | MySQL only |
| XXL-Job (27k★) | Dedicated scheduler + DB | Embedded, no extra server |
| Leaf (4.5k★) | Dedicated Leaf server | Embedded starter |
| Stripe-style idempotency | API gateway / Redis | One annotation, MySQL uk claim |
| Quota / inventory oversell guards | Custom concurrent accounting | Conditional UPDATE with invariant in WHERE |

## Quick Start

**From source (until Maven Central publish):**

```bash
git clone https://github.com/Mein-Augenstern/star-site-components.git
cd star-site-components && ./mvnw install -DskipTests
```

**Add dependencies:**

```xml
<dependency>
    <groupId>com.xp.starsite</groupId>
    <artifactId>starsite-sequence-spring-boot-starter</artifactId>
    <version>0.2.3</version>
</dependency>
<dependency>
    <groupId>com.xp.starsite</groupId>
    <artifactId>starsite-async-command-spring-boot-starter</artifactId>
    <version>0.2.3</version>
</dependency>
<dependency>
    <groupId>com.xp.starsite</groupId>
    <artifactId>starsite-lock-spring-boot-starter</artifactId>
    <version>0.2.3</version>
</dependency>
<dependency>
    <groupId>com.xp.starsite</groupId>
    <artifactId>starsite-schedule-spring-boot-starter</artifactId>
    <version>0.2.3</version>
</dependency>
<dependency>
    <groupId>com.xp.starsite</groupId>
    <artifactId>starsite-idempotent-spring-boot-starter</artifactId>
    <version>0.2.3</version>
</dependency>
```

```yaml
starsite:
  sequence:
    mode: mysql              # auto-leases workerId from sys_sequence table
  async-command:
    mode: mysql              # MySQL-backed task queue with retry
  lock:
    mode: mysql                 # MySQL unique-key distributed lock
```

No `@Import`, no `@EnableScheduling` — auto-configuration handles everything.
Annotate a method with `@StarsiteScheduled(cron = "0 0 8 * * ?")` for cluster-wide exactly-once
scheduling; annotate an order API with `@StarsiteIdempotent(key = "#orderId")` for
exactly-once execution with **first-result replay** on duplicates.

For MyBatis consumers: same-package Mapper XML auto-discovery — zero `mapper-locations` config needed.
For no-DB gateway: set `starsite.async-command.enabled=false` + `starsite.lock.enabled=false`.

## Key Design Decisions

| Decision | Why |
|---|---|
| Store-side reentrant counting | Lock hold count lives in DB column / Redis hash — not Pod memory |
| SQL externalized to XML | DBAs can audit; dialect switching without Java changes |
| Index completeness tests | Unit tests assert PK + unique indexes exist (DDL drift caught at CI) |
| List/detail column separation | TEXT columns (64KB payload) excluded from list queries |
| Fail-open on lock errors | Better to have competing instances than a stalled system — note: a **mutex-breach window exists at DB recovery** (fault-injection verified); business side should stay idempotent or use redis mode |

Full decision log: [docs/design.md](docs/design.md)

## Production Deployment Checklist (fault-injection verified)

- **Always set JDBC timeouts** — add `connectTimeout=5000&socketTimeout=30000` to the datasource URL.
  Without them, a DB outage manifests as **silent infinite hangs** (no 5xx, no rejections) and
  `actuator/health` hangs, which triggers cascading Pod restarts under Kubernetes (OBS-106)
- **Pool sizing** — `instances × max-pool-size ≤ MySQL max_connections`; note raising the pool
  only reduces tail latency, it does not raise throughput (the real ceiling is single-node DB writes, OBS-104)
- **Split K8s probes** — liveness at process level, readiness via health groups; a shared health
  probe restarts every instance on DB jitter (OBS-108)

Full data: [benchmark report](docs/benchmark-report.html) · [full-benchmark.md](docs/full-benchmark.md) · [findings](docs/bench-findings.md)

## Build & Test

```bash
./mvnw verify          # Spotless + all unit tests
./mvnw verify -Pit     # + Testcontainers MySQL integration tests
./mvnw spotless:apply  # Auto-fix style
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). CI must be green to merge.

## License

[Apache-2.0](LICENSE)
