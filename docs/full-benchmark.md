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
