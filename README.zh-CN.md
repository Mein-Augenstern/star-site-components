# star-site-components

[![CI](https://github.com/Mein-Augenstern/star-site-components/actions/workflows/ci.yml/badge.svg)](https://github.com/Mein-Augenstern/star-site-components/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

star-site 系列通用组件库：**core（纯库 + MyBatis 可选路径）+ spring-boot-starter（装配）**的开源标准结构，
三个组件均同时考虑**单机与分布式**两种运行形态。引 starter + 配置即用；core 可脱离 Spring Boot 独立引用。

| 组件 | 一句话 | 单机 | 分布式 | 详细文档 |
|---|---|---|---|---|
| **sequence** | 雪花 ID（时钟回拨钳制） | 固定机器位 | MySQL `__global__` 池原子租约 | [docs/sequence.md](docs/sequence.md) |
| **async-command** | 异步命令（令牌认领 / 退避重试 / 死信） | memory 队列 | MySQL 表令牌认领 | [docs/async-command.md](docs/async-command.md) |
| **lock** | 分布式锁（可重入 / 限时等待 / 自愈） | —（天然多实例） | DB 唯一键 / Redis hash | [docs/lock.md](docs/lock.md) |

```
starsite-sequence ──────► starsite-sequence-spring-boot-starter
starsite-async-command ─► starsite-async-command-spring-boot-starter
starsite-lock ──────────► starsite-lock-spring-boot-starter
```

- JDK 21 · Spring Boot 3.2.x · **core 零 Lombok** · Apache-2.0 · 已在 [star-site-backend](https://github.com/Mein-Augenstern/star-site-backend) 生产接入
- 当前全量 **123 项单测**（6 模块全绿；权威声明口径——以 `mvn verify` 实时值为准）

## 快速开始

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

无需 `@Import`、无需 `@EnableScheduling`——自动装配完成一切。非 Spring Boot 项目引 core 手工组装。
消费方 classpath 有 MyBatis 时**零配置自动切换** MyBatis 存储路径；无 DB 的 WebFlux 网关设
`starsite.async-command.enabled=false` + `starsite.lock.enabled=false`。

## 组件表规范（三组件统一铁律）

- **物理主键 id 由 sequence 组件发号**（禁 AUTO_INCREMENT）
- **标准时间列 `gmt_create` / `gmt_modified`**
- **SQL 配置外置铁律**：MyBatis 走 Mapper XML（同包路径=零配置）；JDBC 方言走 `resources/sql/mysql/<组件>-mysql.xml`；Redis Lua 走 `resources/sql/*.lua`——Java 零 SQL 字符串
- **高频路径全索引命中**（如 async_command 认领/幂等/认领取回三件套，lock 全点查 uk）
- **分布式状态存储端**：跨实例共享状态必须存存储端（lock 重入计数=hold_count 列 / Redis hash），Pod 本地仅性能提示缓存且带自愈
- **列表/详情分列**：大列（payload TEXT）不进列表查询

## 设计模式速览

| 模式 | 落点 |
|---|---|
| 策略 | WorkerIdAllocator / AsyncCommandStore / LockClient——`mode`/`store` 一行配置切形态 |
| 门面 | StarsiteIdGenerator / AsyncCommandClient / LockClient——业务只面对一个入口 |
| 适配器 | starter 的 SmartLifecycle 托管（core 零 Spring 依赖） |
| 乐观并发 | DB 令牌认领 / lock_name 唯键单行 UPDATE |

`@ConditionalOnMissingBean` 全量让位 · `@ConditionalOnClass` 按 classpath 装配 · 健康检查被动上报。

## 构建与测试

```bash
mvn verify          # Spotless 门禁 + 全量单测（120 项）
mvn verify -Pit     # 真库 IT（Testcontainers MySQL）
mvn spotless:apply  # 规范自动修复
```

## 代码规范

Spotless 门禁（许可头/导入/空白）· **每个公开方法含 getter/setter 须有 javadoc**（孤例即缺陷） ·
注释写"为什么" · CI 红=不合。详见 [component-builder skill](https://github.com/Mein-Augenstern/constellation/blob/main/skills/component-builder/SKILL.md)。

## 后续规划

已交付：async 四件套（批量/指标/鉴权/调度三模式）· backend 生产接入 · lock 三件套（可重入/限时等待/自愈）· SQL/脚本配置外置 + 索引门禁 + 列表分列

待办：sequence Redis 租约 · lock 看门狗续期 · async 深分页游标化 · 0.1.0 正式版发布

[← 完整规划清单](docs/roadmap.md) · [← 设计决策记录](docs/design.md)
