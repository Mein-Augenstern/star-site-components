# Contributing

Thanks for your interest in contributing!

## Prerequisites

- JDK 21+
- Maven 3.9+ (or use `./mvnw`)
- Docker (for integration tests)

## Getting Started

```bash
git clone https://github.com/Mein-Augenstern/star-site-components.git
cd star-site-components
./mvnw verify        # Spotless check + all unit tests
./mvnw verify -Pit   # + integration tests (requires Docker with MySQL 8.0 image)
```

## Code Standards (enforced by CI)

- **Spotless gate**: Apache-2.0 license header, unused import removal, trailing whitespace
- **Javadoc**: every public class/interface/method (including getters/setters) must have at least one line of semantic documentation
- **SQL externalization**: all SQL in Mapper XML or `resources/sql/mysql/*.xml` dialect files — zero SQL strings in Java
- **Tests**: every bug fix needs a regression test; every feature needs at least one unit test
- **Comments**: explain *why*, not *what*

## Pull Request Process

1. Fork → create feature branch → commit → push → open PR
2. CI must be green (Spotless + unit tests + integration tests)
3. One approval required for merge
4. Conventional commit messages preferred (`feat:`, `fix:`, `docs:`, `refactor:`)

## Reporting Issues

Please include:
- Component (sequence / async-command / lock)
- Spring Boot version
- Configuration snippet
- Stack trace (if applicable)
- Steps to reproduce
