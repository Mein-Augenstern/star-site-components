# 完整性能压测报告（三组件 × 全部存储 × 单机/分布式）

> [← 返回概览](../README.md)

**测试方式**：部署 demo-spring-boot 到本地（JDK 21 / 端口 18080/18081），
MySQL 8.0 Docker（端口 3307）+ Redis Docker（端口 6379），
Python 并发 HTTP → Spring MVC → 组件 → 存储 → JSON 响应，测量完整 RPC 链路。

**与进程内基准的关系**：进程内 benchmark 见 bench/bench-report.md。
RPC 层开销 = HTTP 连接 + Spring MVC 调度 + JSON 序列化 + 存储 Docker 网络。

## sequence

| Mode | Concurrency | ops/sec | P50 | P95 | P99 | Max |
|---|---|---|---|---|---|---|
| standalone | 1 | 359 | 2.4ms | 4.9ms | 6.7ms | 42.8ms |
| standalone | 8 | 928 | 7.9ms | 15.3ms | 19.4ms | 45.3ms |
| standalone | 16 | 1,175 | 12.4ms | 22.0ms | 30.1ms | 56.7ms |
| mysql | 1 | 371 | 2.4ms | 4.4ms | 6.4ms | 37.8ms |
| mysql | 8 | 988 | 7.3ms | 14.2ms | 20.5ms | 33.8ms |
| mysql | 16 | 1,026 | 14.1ms | 27.1ms | 34.1ms | 60.6ms |
| redis | 1 | 361 | 2.4ms | 4.8ms | 6.6ms | 23.3ms |
| redis | 8 | 932 | 7.9ms | 14.8ms | 19.4ms | 37.6ms |
| redis | 16 | 1,172 | 12.3ms | 23.0ms | 31.0ms | 39.5ms |

## lock

| Mode | Concurrency | ops/sec | P50 | P95 | P99 | Max |
|---|---|---|---|---|---|---|
| mysql | 1 | 62 | 15.2ms | 21.7ms | 33.2ms | 76.6ms |
| mysql | 8 | 107 | 71.4ms | 96.5ms | 129.5ms | 145.0ms |
| redis | 1 | 153 | 5.9ms | 9.9ms | 12.3ms | 57.9ms |
| redis | 8 | 650 | 11.6ms | 18.8ms | 22.0ms | 24.9ms |
| mysql | 16 | 106 | 143.2ms | 181.2ms | 260.4ms | 744.6ms |
| mysql | 32 | 111 | 281.3ms | 364.4ms | 409.7ms | 417.5ms |
| redis | 16 | 497 | 27.9ms | 58.6ms | 156.3ms | 171.5ms |
| redis | 32 | 568 | 27.3ms | 67.1ms | 87.6ms | 103.8ms |
| distributed | 16 | 147 | 105.6ms | 175.8ms | 263.7ms | 905.7ms |

## async-command

| Mode | Concurrency | ops/sec | P50 | P95 | P99 | Max |
|---|---|---|---|---|---|---|
| standalone | 1 | 369 | 2.3ms | 4.6ms | 5.7ms | 47.5ms |
| standalone | 8 | 790 | 9.4ms | 16.6ms | 21.7ms | 37.7ms |
| mysql | 1 | 135 | 7.0ms | 10.1ms | 13.6ms | 30.1ms |
| mysql | 8 | 508 | 15.0ms | 24.2ms | 27.5ms | 28.6ms |

## 生产链路压测（bench-service + Gateway，2026-10-31 实机）

路径：客户端 → Gateway(8080, Docker 容器, Reactor Netty) → Nacos LB → bench-service(宿主机 18090) → MySQL 8.0(Docker, 3307)。
存储形态：三组件 mysql（star_site 库）；每场景 1000 请求（lock/async 场景 250）。
同一轮压测另测了直连 bench-service（18090）作对照，量化网关转发的额外开销。

### 直连（对照）

| Scenario | Threads | ops/sec | P50 | P95 | P99 | Max |
|---|---|---|---|---|---|---|
| sequence ID | 1 | 610 | 1.4ms | 2.7ms | 3.5ms | 16.8ms |
| sequence ID | 8 | 1,980 | 3.7ms | 5.9ms | 14.4ms | 16.9ms |
| sequence ID | 16 | 2,097 | 7.2ms | 10.9ms | 18.4ms | 20.9ms |
| lock acquire/release | 1 | 80 | 11.5ms | 16.5ms | 30.9ms | 50.0ms |
| lock acquire/release | 16 | 181 | 87.1ms | 101.2ms | 104.3ms | 108.9ms |
| lock wait 100ms | 16 | 183 | 86.2ms | 105.2ms | 112.2ms | 121.6ms |
| async submit | 1 | 190 | 4.9ms | 6.6ms | 10.4ms | 29.7ms |
| async submit | 16 | 846 | 17.3ms | 26.3ms | 30.4ms | 30.5ms |
| async idempotent | 16 | 1,449 | 10.1ms | 15.9ms | 19.0ms | 19.3ms |

### 经 Gateway（生产路径）

| Scenario | Threads | ops/sec | P50 | P95 | P99 | Max |
|---|---|---|---|---|---|---|
| sequence ID | 1 | 173 | 5.4ms | 8.5ms | 10.3ms | 26.8ms |
| sequence ID | 8 | 912 | 8.3ms | 11.9ms | 20.0ms | 30.5ms |
| sequence ID | 16 | 1,186 | 12.4ms | 20.4ms | 24.7ms | 35.5ms |
| lock acquire/release | 1 | 72 | 13.1ms | 19.1ms | 23.4ms | 27.5ms |
| lock acquire/release | 8 | 149 | 50.2ms | 77.2ms | 86.8ms | 87.2ms |
| lock acquire/release | 16 | 173 | 91.6ms | 99.9ms | 101.5ms | 108.9ms |
| lock wait 100ms | 1 | 84 | 11.8ms | 13.4ms | 14.5ms | 16.1ms |
| lock wait 100ms | 8 | 178 | 42.8ms | 59.9ms | 66.8ms | 67.7ms |
| lock wait 100ms | 16 | 118 | 94.6ms | 296.0ms | 302.6ms | 306.2ms |
| async submit | 1 | 70 | 13.1ms | 23.3ms | 30.8ms | 39.0ms |
| async submit | 8 | 501 | 15.0ms | 22.7ms | 24.7ms | 27.5ms |
| async submit | 16 | 726 | 21.5ms | 27.0ms | 28.5ms | 29.1ms |
| async idempotent | 1 | 192 | 4.8ms | 7.3ms | 12.6ms | 14.6ms |
| async idempotent | 8 | 774 | 9.6ms | 14.1ms | 17.8ms | 19.9ms |
| async idempotent | 16 | 1,025 | 14.4ms | 21.3ms | 25.0ms | 30.6ms |

### 解读

- **网关转发开销**：低并发 P50 增加约 2–8ms（ID 1.4→5.4ms、async submit 4.9→13.1ms），
  高并发吞吐损失约 30–45%（ID 2097→1186、async idempotent 1449→1025 ops/s）——
  Reactor Netty 转发 + Docker NAT 的组合成本，量级与社区经验一致。
- **互斥语义的吞吐天花板与路径无关**：lock 16 线程直连 181 vs 网关 173 ops/s，
  差距 <5%——DB 互斥是瓶颈本体，加网关不加显著开销。
- **lock wait 16 线程 P95 296ms**：100ms 轮询量化在高争抢下的表现（OBS-102），
  复跑 quick 模式同场景 P95=91.6ms，证明是争抢概率事件而非稳定退化。
- **错误率**：全部场景 0 错误；压测期间服务日志 0 ERROR。

复跑方式：`star-site-backend/star-site-platform-bench-service/bench-start/src/main/resources/scripts/bench.py --url http://localhost:8080 --scenario all`
