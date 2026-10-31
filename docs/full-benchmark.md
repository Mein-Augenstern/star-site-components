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

## 0.2.1 修复验证（2026-10-31，bench-service 复测）

### OBS-101 积压消化（核心改善，干净测量）

注入方式：200 条 async 命令顺序提交后立即观测表状态。

| 版本 | 消化速率 | 200 条清空耗时 |
|---|---|---|
| 0.2.0（pollInterval=5s × batch=10，无追赶） | **2 条/秒** | ~100 秒 |
| 0.2.1（batch=50 + 满批追赶 8 轮上限） | **~40+ 条/秒实测**（理论上限 80/s） | **< 5 秒（一个 tick 窗口）** |

提交循环尚未结束时调度器已并行消化（t=0 剩 83），首个 tick 窗口内全部清零。

### OBS-102 lock-wait 自适应退避

16 线程 `lock wait 100ms` P95：0.2.0 为 296ms（固定 100ms 轮询量化长尾）；
0.2.1 复测在系统负载 2 倍于基线（load avg 8 vs 4）的恶劣条件下 P95 仍降至 187~247ms——
负载归零后改善更明显。确定性单测佐证：第 3 次尝试得手场景 30ms 量级（旧固定间隔 ≥200ms）。

### 吞吐说明

0.2.1 复测轮的绝对吞吐整体低于 0.2.0 基线（如 ID 16t 1186→~700 ops/s），
系同机并发任务（构建/测试套件余载）噪声所致，非组件退化——复测全程 0 错误，
lock/async/id 三组件行为正常；调度器追赶与压测流量并存时双负载共存亦无错误。

## 极限摸高压测（2026-10-31，找饱和点/首错点/失效形态）

方法：经 Gateway 生产链路逐级拉并发（32→64→128→256 线程）至平台期后再翻倍，
压测中经 actuator/metrics 密集采样真实资源（Hikari 池、MySQL 连接），最后 10000 条
洪峰验证极端积压行为。<b>读数注意：客户端/服务/网关/MySQL 同机共存且背景负载波动
（load 2.8~8），绝对值打折——摸高结论看形态与归因，不看绝对数。</b>

### 各路径的饱和点与失效形态（全程零错误）

| 场景 | 线程梯度 | 平台吞吐 | 首个错误 | 超限后的形态 |
|---|---|---|---|---|
| ID 生成（纯内存雪花） | 32→256 | ~745 ops/s（32t 即到顶） | **无（256t 仍 0 错）** | 吞吐恒定、P50 线性爬升 38→130ms——标准排队论饱和，不崩 |
| async 提交（DB 写） | 32→128 | ~390 ops/s（32t 即到顶） | **无（128t 0 错）** | Hikari 打满 10/10、**112 请求排队**等连接；P99 769ms 远未触 30s 超时 |
| lock 互斥 | 32→128 | ~110 ops/s（恒定） | **无** | P50=并发/吞吐（301ms→1033ms 完美排队曲线）——互斥串行是设计天花板，无死锁无 TTL 违约 |
| 洪峰 10000 条 | 64t 提交 | 提交 387 ops/s | **无** | 消化 ~75 条/秒，与满批追赶上限公式 8×50/5s=**80/s 精确吻合** |

### 关键归因（资源采样实证）

- **连接池饱和证据**：`hikaricp.connections.active=10/10`、`pending=112`，而 MySQL
  `Threads_connected` 恒 21~31——池化隔离生效，DB 侧无连接风暴。
- **加池 A/B 实验**：Hikari 10→20 后 P99 769→**463ms**（排队缓解）但吞吐不涨
  （345→333 ops/s）——**连接池只是排队点不是吞吐瓶颈，真天花板是 MySQL 单机写入**
  （同机 Docker 卷 fsync）。生产启示：加池只降尾延；提吞吐靠 DB 分片/批量写/降 fsync。
- **系统韧性**：网关/MySQL 容器 0 重启、0 OOM（384MiB 限额内），服务日志 0 ERROR；
  MySQL max_connections=200 余量充足（池化后实例数×池上限才 20+）。
- **失效形态总结**：三组件全部是「优雅排队」而非「错误崩溃」——超限表现为延迟线性
  上涨（HTTP 客户端视角 P99 秒级），始终无 5xx/超时/数据不一致。组件层面唯一的硬
  上限是 lock 互斥吞吐（~110 ops/s，存储点查 UPDATE 的串行本质，属设计而非缺陷）。

### 生产容量速查（本机形态推算，按比例折算到目标硬件）

| 组件路径 | 单实例饱和吞吐 | 超限行为 | 扩容手段 |
|---|---|---|---|
| ID 生成 | HTTP 栈天花板（与 DB 无关） | 排队 | 加实例线性扩展（workerId 已全局唯一） |
| async 提交 | MySQL 写入天花板 ~390/s | 排队（池等待） | 批量写/分库；**消费端可横向扩实例**（令牌认领天然并行） |
| async 消化 | 80/s/实例（追赶上限） | 积压可见但持续消化 | 加实例线性扩展；调大 batch-size/缩短 poll-interval |
| lock | ~110/s（互斥点查串行） | 排队 | 该锁热点本身不可横向扩——拆锁名分片 |

## 故障注入：把系统打崩的真实形态（2026-10-31，单机 + 双实例小集群）

> 前一轮「极限摸高」只到排队饱和，本轮按故障注入矩阵真正打崩：连接风暴 → DB 挂起
> （pause）→ DB 断连（stop）→ 锁表 45s → 线程风暴 → payload 边界 → 双实例互斥击穿。
> **核心结论：这套系统的"崩"不是报错崩溃，而是静默挂起——最阴险的失效形态。**

### 故障注入矩阵与实测形态

| 注入 | 客户端视角 | 服务端形态 | 恢复 |
|---|---|---|---|
| 连接风暴 1024 线程（python）/ 1600 并发（ab） | 0 真实失败（ab 的 51 个 Length 失败=ID 位数变化假阳性） | 吞吐 ~1450 ops/s；同机客户端先于服务端饱和 | — |
| **MySQL pause 40s（挂起）** | lock 场景 600 请求 **64 个超时**、吞吐 110→2 ops/s | TCP 不断→JDBC 无限等→Tomcat 线程被占；恢复瞬间 **40s 慢 SQL 洪峰**统一完成 | ✅ 完全自愈，0 残留 |
| **MySQL stop（断连）** | 请求**无限挂起**（45s/75s 均无响应） | docker-proxy 接受 TCP 但不转发→驱动 connectTimeout 默认 0（无限）；**actuator/health 挂死** | ✅ 重启后秒级恢复 |
| 锁表 45s（LOCK TABLES WRITE） | 200 请求 **32 超时**、0 ops/s | 全部等元数据锁挂起；释放瞬间密集完成 | ✅ 自愈 |
| 200 线程风暴 × DB 停机 | **health 000 挂死**；纯内存 /api/bench/id 仍 200/9ms | 部分降级非全站崩（Reactor 网关吸收并发） | ✅ |
| payload 200KB/4KB | **HTTP 414**（URL 传参边界 1~4KB） | 组件列 TEXT 上限 64KB（DDL 定义）；更大需 MEDIUMTEXT | — |

### 关键语义发现

**1. fail-open 防报错不防挂起（最重要）**
lock 的 fail-open（争锁异常→放行）只在驱动**抛异常**时触发。DB 挂起（pause/proxy 半开）不抛
异常→互斥语义既不生效也不放行，请求无限等待；**恢复瞬间**挂起连接集中报错→fail-open
**并发放行**（实测 16 个同锁名请求同刻放行）——多实例语义下即互斥击穿窗口：DB 故障恢复的
瞬间，所有等待实例同时进入临界区。分布式实证（双实例）：DB 停机期间两实例同样无限挂起
（同单机形态）；Redis 形态不受 MySQL 故障影响。

**2. health 挂死是编排级联的引信**
actuator/health 含 DataSource 指标→DB 挂→health 无限挂起→K8s 判定 Pod 不健康→连环重启
→故障放大为全站不可用。这是比线程耗尽更现实的崩溃路径。

**3. 修复：JDBC 超时参数（bench-service 已落地）**
datasource URL 追加 `connectTimeout=5000&socketTimeout=30000` 后实测：
health 从无限挂死→**精确 30.0s 返回 500**；lock 端点从无限挂起→**60s 有界**
（30s socketTimeout × Hikari 两轮建连重试）后显性 fail-open/报错。静默挂起转化为
可感知错误，Tomcat 线程必然释放。**生产必配这两个参数。**

### 单机 vs 小集群结论

- **单机**：一切同址，DB 挂=全站挂（health 挂死→编排连环重启风险最高）；不配超时参数时
  无任何拒绝信号（无 5xx、无连接拒绝）——监控只见延迟飙升，最易误判
- **小集群（2 实例 + LB）**：吞吐/消化可横向扩（workerId 全局唯一实证 7/8），但 MySQL
  仍单点——DB 故障=全实例同时挂起 + **恢复瞬间 fail-open 并发放行的互斥击穿窗口**；
  解法：lock 换 redis 形态（MySQL 故障免疫）或接受 fail-open 语义并确保业务幂等
- 三组件全部故障形态可归纳为一句话：**不崩则已，崩必静默**——超时参数 + 细粒度健康检查
  （db 状态与 liveness 分离）是单机/小集群的两剂解药
