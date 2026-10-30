# star-site-components

[![CI](https://github.com/Mein-Augenstern/star-site-components/actions/workflows/ci.yml/badge.svg)](https://github.com/Mein-Augenstern/star-site-components/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2+-brightgreen.svg)](https://spring.io/projects/spring-boot)

**[中文文档](README.zh-CN.md)**

Lightweight distributed components for Spring Boot 3 — **DB-first, only needs MySQL**.

Three production-proven components that compose together:

| Component | What it does | Single machine | Distributed | Docs |
|---|---|---|---|---|
| **sequence** | Snowflake ID generator (clock-backward safe) | Fixed worker ID | MySQL atomic lease | [docs/sequence.md](docs/sequence.md) |
| **async-command** | Async task queue (retry, dead letter, batch) | In-memory queue | MySQL token claim | [docs/async-command.md](docs/async-command.md) |
| **lock** | Distributed lock (reentrant, timed wait, self-healing) | — | DB unique key / Redis hash | [docs/lock.md](docs/lock.md) |

```
starsite-sequence ──────► starsite-sequence-spring-boot-starter
starsite-async-command ─► starsite-async-command-spring-boot-starter
starsite-lock ──────────► starsite-lock-spring-boot-starter
```

- JDK 17+ · Spring Boot 3.2+ · Zero Lombok in core · Apache-2.0

| JDK | Supported | Note |
|---|---|---|
| 17 | ✅ | Minimum (Spring Boot 3 requirement) |
| 21 | ✅ | Recommended |
| 8/11 | ❌ | Requires Spring Boot 2.x (not supported) |
- **123 unit tests** + Testcontainers MySQL integration tests
- Production-proven in [star-site-backend](https://github.com/Mein-Augenstern/star-site-backend) (12 microservices, 18 containers)

## Why This Exists

Most distributed lock libraries need Redis. Most task schedulers need a dedicated server. Most ID generators need a separate service.

**If you already have MySQL, you're done** — add three starters and get all three capabilities with zero additional infrastructure.

| Alternative | Requires | This project |
|---|---|---|
| Redisson (10k★) | Redis cluster | MySQL (already have it) |
| ShedLock (3.5k★) | Redis / JDBC / ZooKeeper | MySQL only |
| XXL-Job (27k★) | Dedicated scheduler + DB | Embedded, no extra server |
| Leaf (4.5k★) | Dedicated Leaf server | Embedded starter |

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
    <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.xp.starsite</groupId>
    <artifactId>starsite-async-command-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.xp.starsite</groupId>
    <artifactId>starsite-lock-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
starsite:
  sequence:
    mode: mysql              # auto-leases workerId from sys_sequence table
  async-command:
    store: jdbc              # MySQL-backed task queue with retry
  lock:
    mode: mysql                 # MySQL unique-key distributed lock
```

No `@Import`, no `@EnableScheduling` — auto-configuration handles everything.

For MyBatis consumers: same-package Mapper XML auto-discovery — zero `mapper-locations` config needed.
For no-DB gateway: set `starsite.async-command.enabled=false` + `starsite.lock.enabled=false`.

## Key Design Decisions

| Decision | Why |
|---|---|
| Store-side reentrant counting | Lock hold count lives in DB column / Redis hash — not Pod memory |
| SQL externalized to XML | DBAs can audit; dialect switching without Java changes |
| Index completeness tests | Unit tests assert PK + unique indexes exist (DDL drift caught at CI) |
| List/detail column separation | TEXT columns (64KB payload) excluded from list queries |
| Fail-open on lock errors | Better to have competing instances than a stalled system |

Full decision log: [docs/design.md](docs/design.md)

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
