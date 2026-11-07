demo-spring-boot · 5 分钟跑通四组件
====

一个 Spring Boot 应用，同时演示 starsite 四组件：**sequence**（分布式 ID）、
**async-command**（异步命令重试）、**lock**（分布式锁）、**schedule**（定时任务恰一执行）。
跑通后你会看到：雪花 ID 生成、异步命令落库重试、双实例只有一个执行定时任务、执行流水可查。

前置
----

* JDK 17+、Maven、Docker（MySQL 8）
* 组件 jar 在本地仓库：克隆本仓库后 `mvn install -DskipTests`（约 2 分钟；
  Maven Central 上架进行中，见 issue #1）

三步跑通
----

```bash
# 1. 起数据库（demo 自动建表：async_command / starsite_lock / schedule_run_log）
docker run -d --name demo-mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8.0

# 2. 编译打包（组件已 install 到本地仓）
mvn package -DskipTests

# 3. 跑两个实例（演示恰一执行——同一台机器两个端口即可看效果）
java -jar target/demo-spring-boot-0.1.0.jar --server.port=8080 &
java -jar target/demo-spring-boot-0.1.0.jar --server.port=8081 &
```

验证四件事
----

```bash
# ① 雪花 ID（sequence）
curl localhost:8080/api/demo/id

# ② 提交异步命令（async-command：落库 → 调度认领 → 执行重试）
curl -X POST "localhost:8080/api/demo/notify?userId=u1&message=hi"

# ③ 分布式锁（lock）：抢占即释放（双实例时可见互斥）
curl "localhost:8080/api/demo/lock"

# ④ 定时任务恰一执行（schedule）：每 10 秒触发，两实例只有一台执行
mysql -h127.0.0.1 -uroot -proot starsite_demo \
  -e "SELECT task_name, host, status FROM schedule_run_log ORDER BY id DESC LIMIT 4;"
```

`schedule_run_log` 里你会看到：同一秒触发只产生一行 DONE（获锁实例执行），
另一实例写 `SKIPPED_LOCKED`（让位留痕）——这就是"恰一执行"的流水证据。

看什么代码（4 个文件，全部不到 40 行）
----

| 文件 | 演示 |
|---|---|
| `DemoApplication.java` | 零配置启动——starter 自动装配一切 |
| `DemoController.java` | sequence 发号 / async-command 提交 / lock 手动抢占 |
| `NotificationExecutor.java` | 异步命令的执行器（声明 cmdType 即被路由） |
| `ScheduledJob.java` | **一行注解** `@StarsiteScheduled`：锁+流水+时区全被吸收 |

与生产的关系
----

demo 用 `--server.port` 双实例模拟集群；生产中就是 Kubernetes 多副本。
组件的存储三形态（memory/jdbc/mybatis）由 `starsite.*.mode` 一行配置切换——
demo 用 mysql 形态，单机测试可改 memory 零依赖。
