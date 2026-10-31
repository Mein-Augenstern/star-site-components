# RPC Benchmark Report（端到端性能）

> [← 返回概览](../README.md)

**测试方式**：部署 demo-spring-boot 到本地（JDK 21 / 端口 18080），MySQL 8.0 Docker 容器，
Python 并发 HTTP 请求 → Spring MVC → 组件 → MySQL → JSON 响应，测量完整 RPC 链路延迟。

**与进程内基准的对比**：进程内 benchmark 见 bench/bench-report.md——RPC 层的额外开销
来自 HTTP 连接、Spring MVC 调度、JSON 序列化/反序列化、MySQL Docker 网络往返。

> Target: http://localhost:18080
> Storage: MySQL (Docker)

| Scenario | Concurrency | ops/sec | P50 | P95 | P99 | P99.9 | Max |
|---|---|---|---|---|---|---|---|
| RPC: snowflake ID (1 thread) | 1 | 389 | 2.2ms | 4.3ms | 5.9ms | 11.1ms | 39.0ms |
| RPC: snowflake ID (8 threads) | 8 | 1,224 | 5.7ms | 11.8ms | 17.2ms | 51.0ms | 52.4ms |
| RPC: snowflake ID (16 threads) | 16 | 1,203 | 11.4ms | 20.6ms | 27.6ms | 101.9ms | 122.3ms |
| RPC: lock acquire/release (1 thread) | 1 | 61 | 15.4ms | 23.1ms | 30.6ms | N/A | 68.8ms |
| RPC: lock acquire/release (8 threads) | 8 | 132 | 59.0ms | 74.4ms | 100.2ms | 418.1ms | 419.2ms |
| RPC: async submit (1 thread) | 1 | 137 | 6.8ms | 10.8ms | 13.1ms | N/A | 50.2ms |
| RPC: async submit (8 threads) | 8 | 469 | 14.7ms | 26.0ms | 60.1ms | 306.6ms | 340.5ms |
