# 设计决策记录

> [← 返回概览](../README.md)

三组件的关键设计决策与"为什么"，按时间序。

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
