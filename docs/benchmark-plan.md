# 三组件压测规划

> 目标：给三组件建立**可重复、可对比、有基线**的性能测试体系，
> 覆盖单机与分布式两种场景，产出可对外发布的性能数据。

## 场景矩阵（9 组核心基准）

### sequence（3 组）

| # | 场景 | 并发 | 存储 | 度量 |
|---|---|---|---|---|
| S1 | 单线程发号基线 | 1 | 内存（SnowflakeIdGenerator 直调） | ops/sec, P50/P95/P99/P999 |
| S2 | 多线程同 workerId 竞争 | 1/4/8/16/32 | 内存 | ops/sec（总吞吐）, 冲突重试次数, 唯一性验证 |
| S3 | MySQL 租约 vs standalone 启动时延 | 1 | MySQL(H2 兼容模式) | workerId 分配耗时, workerId 唯一性 |

### lock（3 组）

| # | 场景 | 并发 | 存储 | 度量 |
|---|---|---|---|---|
| L1 | 无竞争 acquire/release | 1 | H2 | P50/P95/P99（μs 级）, ops/sec |
| L2 | 有竞争 N 线程抢同一锁 | 8/16/32 | H2 | 成功率, 平均等待时间, 公平性（各线程获得次数方差） |
| L3 | 可重入嵌套 + TTL 过期接管 | 2 | H2 + 假时钟 | TTL 过期后接管时延, 重入计数正确性 |

### async-command（3 组）

| # | 场景 | 并发 | 存储 | 度量 |
|---|---|---|---|---|
| A1 | 单机提交吞吐（memory） | 1/4/8 | InMemory | submit ops/sec, submit P99 |
| A2 | 端到端时延（submit→execute→SUCCESS） | 1/4 | H2 + MyBatis | P50/P95/P99 端到端, claim 竞争开销 |
| A3 | 多实例认领公平性 | 2/4 "实例" | H2 + MyBatis | 各实例认领条数方差, 认领互斥正确性 |

## 指标定义

| 指标 | 定义 | 工具 |
|---|---|---|
| **ops/sec** | 每秒完成操作数 | 计数器 / 耗时 |
| **P50/P95/P99/P999** | 百分位延迟 | LatencyRecorder（RingBuffer + 排序） |
| **唯一性** | 生成的 ID 集合大小 == 总数 | HashSet 校验 |
| **公平性** | 各竞争者获得资源的方差 | 每线程计数 → 标准差 |
| **端到端时延** | submit() 返回 → executor 执行完成 | System.nanoTime 差值 |

## 工具选型

| 层 | 选择 | 理由 |
|---|---|---|
| 微基准框架 | **自建**（非 JMH） | 三组件均为 I/O 密集（DB/Redis），非纯 CPU 热路径；需协调多线程竞争；JMH 的 fork/warmup 模型对 I/O 场景过度设计 |
| 延迟记录 | **HdrHistogram** | 业界标准百分位库，零分配，内存精确 |
| 存储隔离 | **H2 MySQL 兼容模式** | 本地零依赖跑分布式语义（LAST_INSERT_ID/ON DUPLICATE KEY 均支持）；真 MySQL 走 `-Pbench-mysql` |
| 报告 | **Markdown 表格** | 可直接贴到 GitHub PR / docs/ |

## 基准框架设计

```
bench/
├── src/main/java/com/starsite/bench/
│   ├── BenchRunner.java          ← 主入口：选择场景、执行、汇总
│   ├── BenchResult.java          ← 结果 POJO（ops/sec + 百分位 + 校验）
│   ├── BenchReporter.java        ← Markdown 报告生成器
│   ├── LatencyRecorder.java      ← HdrHistogram 封装
│   ├── scenario/
│   │   ├── SequenceBench.java    ← S1/S2/S3
│   │   ├── LockBench.java        ← L1/L2/L3
│   │   └── AsyncBench.java       ← A1/A2/A3
│   └── infra/
│       └── H2Infra.java          ← H2 库 + 建表 + MyBatis 装配
├── pom.xml                       ← 独立模块（不在主 reactor，手动运行）
└── run-bench.sh                  ← 便捷入口
```

## 运行方式

```bash
# 默认（H2 内存库，全场景）
cd bench && mvn compile exec:java -Dexec.mainClass=com.starsite.bench.BenchRunner

# 仅 lock 场景
mvn exec:java -Dexec.mainClass=com.starsite.bench.BenchRunner -Dexec.args="lock"

# 真 MySQL（需 docker compose up）
mvn exec:java ... -Dbench.mysql=true
```

## 预期基线（基于架构分析的量级预估）

| 场景 | 预期 ops/sec | 预期 P99 |
|---|---|---|
| S1 单线程发号 | >100 万 | <1μs |
| S2 32 线程竞争 | >200 万（总量） | <10μs |
| L1 无竞争锁 | >5 千 | <200μs |
| L2 32 线程竞争 | ~1 千 | <5ms |
| A1 内存提交 | >50 万 | <10μs |
| A2 端到端 | ~2 千 | <50ms |
