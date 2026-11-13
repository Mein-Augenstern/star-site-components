# 设计决策记录

> [← 返回概览](../README.md)

组件库的关键设计决策与"为什么"，按时间序。

## D1 · core/starter 双模块（2026-10 初）

**决策**：core 零 Spring Boot 依赖，starter 仅粘合。
**为什么**：组件是库不是应用——core 可被非 Boot 项目引用；starter 只做装配让位，应用自定义 Bean 优先。

## D2 · 表三字段规范 + sequence 发号（2026-10-24）

**决策**：所有组件表 id 由 sequence 组件发号（禁 AUTO_INCREMENT），标配 gmt_create/gmt_modified。
**为什么**：id 在 insert 前可得（跨服务引用/事件载荷），无 DB 自增锁竞争；时间列统一运维口径。

## D3 · MyBatis Mapper XML 同包路径（2026-10-24）

**决策**：XML 放 resources 下与接口同包路径（如 `resources/com/xp/starsite/asynccmd/mybatis/`）。
**为什么**：MyBatis 官方约定——`addMapper` 按接口 FQN 同路径找 XML 自动加载；深路径 = 消费方零配置 mapper-locations。
看似怪实为机制本体，勿挪浅目录。

## D4 · 分布式状态存储端铁律（2026-10-27）

**决策**：跨实例共享状态必须存存储端（DB 列 / Redis hash field），Pod 本地只允许性能提示缓存且须配自愈路径。
**为什么**：lock 重入计数曾放实例内存 Map——同 ownerId 两 Pod 各数各的，B 一次 unlock 放掉 A 的外层锁。
改为锁表 hold_count 列（UPDATE 内 CASE 递增/递减）+ Redis hash（field=ownerId, value=层数, Lua 原子）。
播种记忆 seeded 同理：纯性能提示，未抢到时 findHolder 校验存活性自愈。

## D5 · SQL 配置外置铁律（2026-10-30）

**决策**：Java 代码零 SQL 字符串。MyBatis 走 Mapper XML；JDBC 方言走 `resources/sql/mysql/<组件>-mysql.xml`；
Redis Lua 走 `resources/sql/*.lua`。动态过滤/分页排序片段也是方言方法。
**为什么**：SQL 在 Java 里既不可读也不可审计（DBA 拿不到），且方言切换需改 Java 代码。
初版曾用 .properties——单行装不下带注释的多行 DDL，当日纠正为 XML（CDATA 承载多行原生 SQL）。

## D6 · 列表/详情分列（2026-10-30）

**决策**：selectRecent/selectPage 显式列清单不含 payload TEXT 大列（单行最大 64KB）；详情/执行走 findById/findByToken 全列。
**为什么**：管控列表页全量拉大列是典型慢点。三形态（jdbc/mybatis/memory）行为等价。

## D7 · 注释规约门禁化（2026-10-30）

**决策**：每个公开方法（含 getter/setter）须有 javadoc，孤例即缺陷。
**为什么**：LockCoreConfig 曾 8 个 getter/setter 裸行，SequenceConfig/AsyncCommandConfig 同类全部有——孤例即缺陷。
component-builder skill 第 9.5 步 + component-reviewer 第 7 尺已固化此规约。

## D8 · README 分层：概览 + 详细文档子文件（2026-10-30）

**决策**：README 只保留概览（组件表/快速开始/铁律/模块图/构建/链接），详细使用手册拆到 docs/<组件>.md。
**为什么**：382 行 README 既是概览又是参考手册——读者要翻很久才找到"引什么依赖怎么配"；GitHub 首屏只应展示"这是什么、怎么开始"。

## D9 · schedule 组件：恰一执行 = 去中心触发 + 中心化决策点（2026-11-02）

**决策**：定时任务"全集群恰一执行"不引入中心调度服务——每实例本地算 cron（触发层去中心），
决策点收敛到 starsite_lock 表一行原子 UPDATE（复用 lock 组件）；执行流水落 schedule_run_log。
**为什么**：SchedulerX 形态（中心服务端选 worker）是规模化终点而非起点——个人/小团队形态下，
零新部署 + 零新中间件的 DB 锁恰一是最优解（invest S09 双容器生产实证）。触发与执行已分层，
未来中心服务接管触发时锁协议与流水不变，注解任务零改动迁移。

**同日关联决策**：
- cron 解析器自研（6 字段，~200 行）而非引第三方——可控可测可读，护城河叙事同 sequence 时钟回拨钳制
- 时区默认 Asia/Shanghai 写死在注解默认值——容器 UTC 陷阱（20:00 实际凌晨 4 点）实证两次，产品化即默认正确
- misfire 从未成功不回溯——历史起点不可界定且 cron 攀爬有 4 年上界（初稿"首启动即补跑"被实现否决）
- 去掉 per-task lockTtlSeconds/timeoutSeconds——lock 组件 TTL 是客户端级配置（per-call TTL 列 v0.2）；
  超时中断线程语义争议大，长任务由悬挂回收（STALE）兜底

## D10 · idempotent 组件：回放而非拦截 + 代际令牌守卫（2026-11-12）

**决策**：API 幂等键做成"首次成功结果回放"（Stripe 同款）而非"重复请求拒绝"；
终态落库以认领时的 expire_at 为代际令牌做双条件守卫（status='PROCESSING' AND expire_at=?）。
**为什么**：拒绝式防重把重试用户推向报错（唯一索引兜底的宿命），回放式让第三次点击拿到
同一个订单号和"成功"——这才是幂等键与缓存/拦截的分水岭；代际令牌源于一个推演出的正确性
漏洞：回收是原地 UPDATE，仅按 status 守卫时旧认领者迟到的 complete 会写脏新认领者的行，
而 expire_at 每代认领必换新，是零成本的代际标识。

**同日关联决策**：
- 失败释放键（新请求重执行）而等待目睹失败不重跑——前者与缓存划清界限（Stripe 5xx 同款），
  后者防失败动作的并发重试风暴（显式重试才走回收）
- 崩溃自愈走惰性回收（新请求驱动三合一条件 UPDATE）而非后台扫描——与 lock 行丢失自愈同哲学
- retention 默认开（与 async-command 默认关相反）——幂等记录是瞬态去重状态而非业务数据，
  expire_at 过期即语义死行；OBS-111 教训前置
- 门面方法名 guard 不用 execute——安全门禁对 execute(String,...) 签名一律按裸 SQL 误报
  （component-builder 第 6 步既有教训，TransactionTemplate 同款）
- root pom 编译器开 -parameters：Spring 6.1 移除 LocalVariableTableParameterNameDiscoverer，
  无此标志 SpEL 参数名不可反射；BPP 另配 #p0/#a0 位置引用兜底

## D11 · idempotent redis 形态：TTL 即键记忆窗口 + SPI 自然键（2026-11-13）

**决策**：redis 形态不做"Redis 当 MySQL 用"的镜像表——键记忆窗口直接用 Redis TTL
（认领时 PEXPIRE，归零即键遗忘）；SPI 终态操作从 `(id, expireAt)` 改为
`(scope, idemKey, expireAt)` 自然键寻址；观测方法（计数/清理）降级为接口缺省实现。
**为什么**：过期语义 Redis 原生就有，自建清理是负资产；自然键是 uk 与 Redis key 的
共同寻址位——一套 SPI 签名服务表/缓存/异构全部形态（第三方存储不被迫接受自增主键
假设，TBase 类接入只实现四原子操作）；观测降级让"不支持全库计数"成为显式选择而非
硬扫（健康端点对 -1 省略微报，不虚报 0）。时间值用定长 ISO 串做 Lua 比较——
字节序即时间序，免 epoch 换算与时区陷阱（容器 UTC 家族实证两次）。

## D12 · 扩展口子统一原则：三层通道 + Bean 让位拉齐（2026-11-13）

**决策**：公司内部落地必然适配自建存储（TBase/OceanBase/Tair）与内部平台（鉴权/监控/
机器位）——五组件统一三层接入通道：L1 配置换连（MySQL 兼容库零代码）、L2 方言 Bean
（`XxxSql` 接口）、L3 SPI 实现（Store/Allocator/Client + Bean 让位）；权威清单沉淀
docs/extension.md，各组件手册互链。
**为什么**：Bean 让位（@ConditionalOnMissingBean）是全库既有铁律，扩展故事本该免费；
审计发现唯一的口子缺口是 sequence——自定义 WorkerIdAllocator 此前仅 mysql+mybatis
路径让位（SequenceMyBatisAutoConfiguration 有条件注解，主装配/redis 嵌套类却内部自建），
standalone/redis 形态下公司平台分配器 Bean 会被无视。拉齐为所有形态 Bean 优先：
扩展一致性 > 内部构建路径的"整洁"（主装配注入 ObjectProvider<WorkerIdAllocator>，
在场即用并日志声明覆盖了哪个形态配置）。
