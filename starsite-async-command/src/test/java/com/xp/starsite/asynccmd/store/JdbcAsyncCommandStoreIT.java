/*
 * Copyright 2026 muyi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.xp.starsite.asynccmd.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.xp.starsite.asynccmd.model.AsyncCommand;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * JDBC 存储集成测试：真库验证建表/提交/令牌认领/悬挂回收/幂等/分页。
 * <ul>
 *   <li>默认：Testcontainers 拉起一次性 mysql:8.0（需要 docker）</li>
 *   <li>设置 {@code TEST_MYSQL_URL}（配 TEST_MYSQL_USER/TEST_MYSQL_PASSWORD，默认 root/root）
 *       时直接用外部库——无 docker 环境也能跑 IT 的标准逃生口</li>
 * </ul>
 *
 * @author muyi
 * @date 2026/10/25
 */
class JdbcAsyncCommandStoreIT {

    /** 外部库直连地址（环境变量 TEST_MYSQL_URL）：非 null 时跳过容器直连外部库，是本类双模式的切换开关 */
    private static final String EXT_URL = System.getenv("TEST_MYSQL_URL");

    /** 一次性 MySQL 容器：仅未设置 TEST_MYSQL_URL 时创建，@AfterAll 统一关闭 */
    private static MySQLContainer<?> container;

    /**
     * 类级准备：未配置外部库地址时拉起一次性 mysql:8.0 容器（库名/账号均为 test）。
     */
    @BeforeAll
    static void startDb() {
        if (EXT_URL == null) {
            container = new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("starsite").withUsername("test").withPassword("test");
            container.start();
        }
    }

    /**
     * 类级清理：停止容器（外部库模式无容器，空操作）。
     */
    @AfterAll
    static void stopDb() {
        if (container != null) {
            container.stop();
        }
    }

    /**
     * 取 JDBC 连接地址：外部库模式取 TEST_MYSQL_URL，容器模式取容器地址。
     *
     * @return JDBC URL
     */
    private static String jdbcUrl() {
        return EXT_URL != null ? EXT_URL : container.getJdbcUrl();
    }

    /**
     * 取数据库用户名：外部库模式取 TEST_MYSQL_USER（默认 root），容器模式取容器账号。
     *
     * @return 用户名
     */
    private static String dbUser() {
        return EXT_URL != null ? env("TEST_MYSQL_USER", "root") : container.getUsername();
    }

    /**
     * 取数据库密码：外部库模式取 TEST_MYSQL_PASSWORD（默认 root），容器模式取容器密码。
     *
     * @return 密码
     */
    private static String dbPassword() {
        return EXT_URL != null ? env("TEST_MYSQL_PASSWORD", "root") : container.getPassword();
    }

    /**
     * 读环境变量。
     *
     * @param key 环境变量名
     * @param def 未设置时的默认值
     * @return 环境变量值；未设置返回 def
     */
    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null ? def : v;
    }

    /**
     * 用例级建表通道：先 DROP 旧表再全新初始化——外部库模式表会跨运行残留必须清场，
     * 容器模式每次新库本无残留，DROP 亦无害。每个用例用独立表名互不干扰。
     *
     * @param table 本用例专属表名
     * @return 全新初始化的存储实例
     * @throws SQLException 直连构造失败
     */
    private JdbcAsyncCommandStore store(String table) throws SQLException {
        rawJdbc().execute("DROP TABLE IF EXISTS " + table);
        return directStore(table);
    }

    /**
     * 无自清理的直构通道（非法表名用例专用）：不能把非法名拼进任何要执行的 SQL（包括 DROP），故绕过 store() 的清场。
     *
     * @param table 任意待校验表名（可以是非法值）
     * @return 校验通过并完成初始化的存储实例；非法名由构造器抛 IllegalArgumentException
     * @throws SQLException 直连构造失败
     */
    private JdbcAsyncCommandStore directStore(String table) throws SQLException {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriver(new com.mysql.cj.jdbc.Driver());
        ds.setUrl(jdbcUrl());
        ds.setUsername(dbUser());
        ds.setPassword(dbPassword());
        // IT 用递增计数器模拟 sequence 组件发号（物理主键来源语义等价）
        java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong(0);
        return new JdbcAsyncCommandStore(new JdbcTemplate(ds), table,
                new AsyncCommandSql.MySql(), true, seq::incrementAndGet);
    }

    /**
     * 测试内直连 SQL 通道（回拨 updated_at 等确定性构造用）。
     *
     * @return 直连目标库的 JdbcTemplate
     * @throws SQLException 直连构造失败
     */
    private JdbcTemplate rawJdbc() throws SQLException {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriver(new com.mysql.cj.jdbc.Driver());
        ds.setUrl(jdbcUrl());
        ds.setUsername(dbUser());
        ds.setPassword(dbPassword());
        return new JdbcTemplate(ds);
    }

    /**
     * 构造一条已到期（nextRunAt = 1 秒前）的 PENDING 命令并落库（claim 用例即取即用）。
     *
     * @param store 目标存储
     * @param type  命令类型
     * @param key   业务键
     * @return 落库后命令（id 已回填）
     */
    private AsyncCommand submit(JdbcAsyncCommandStore store, String type, String key) {
        AsyncCommand cmd = new AsyncCommand();
        cmd.setCmdType(type);
        cmd.setBizKey(key);
        cmd.setPayload("{}");
        cmd.setStatus(AsyncCommand.PENDING);
        cmd.setMaxRetry(5);
        cmd.setRetryCount(0);
        cmd.setNextRunAt(LocalDateTime.now().minusSeconds(1));
        cmd.setOwner("it");
        store.insert(cmd);
        return cmd;
    }

    /**
     * 验证自动建表与提交：init-ddl 建表后 insert 正常，自增 id 回填，findById 能按 id 取回。
     */
    @Test
    void 建表与提交_自增id回填() throws Exception {
        JdbcAsyncCommandStore store = store("async_cmd_t1");
        AsyncCommand cmd = submit(store, "create", "k1");
        assertThat(cmd.getId()).as("insert 后 id 必须回填").isPositive();
        assertThat(store.findById(cmd.getId())).isNotNull();
        assertThat(store.findById(cmd.getId()).getCmdType()).isEqualTo("create");
    }

    /**
     * 验证两步令牌法：claim 认领到期命令置 RUNNING 并可按令牌取回；回写终态后令牌清空（不再按令牌可见）。
     */
    @Test
    void 令牌认领_认领后按令牌取回_完成后清空() throws Exception {
        JdbcAsyncCommandStore store = store("async_cmd_t2");
        submit(store, "claim", "k1");
        submit(store, "claim", "k2");

        int claimed = store.claim("token-1", 10, LocalDateTime.now());
        assertThat(claimed).isEqualTo(2);
        List<AsyncCommand> mine = store.findByToken("token-1");
        assertThat(mine).hasSize(2);
        assertThat(mine).allMatch(c -> AsyncCommand.RUNNING.equals(c.getStatus()));

        AsyncCommand one = mine.get(0);
        one.setStatus(AsyncCommand.SUCCESS);
        one.setClaimToken(null);
        store.updateState(one);
        assertThat(store.countByStatus(AsyncCommand.SUCCESS)).isEqualTo(1);
        assertThat(store.findByToken("token-1")).hasSize(1);
    }

    /**
     * 验证多实例认领互斥：两令牌先后认领各 2 条，批次 id 完全不相交（UPDATE 原子性）。
     */
    @Test
    void 双实例认领互斥_批次不相交() throws Exception {
        JdbcAsyncCommandStore store = store("async_cmd_t3");
        for (int i = 0; i < 4; i++) {
            submit(store, "race", "k" + i);
        }
        assertThat(store.claim("instance-a", 2, LocalDateTime.now())).isEqualTo(2);
        assertThat(store.claim("instance-b", 2, LocalDateTime.now())).isEqualTo(2);
        List<Long> idsA = store.findByToken("instance-a").stream().map(AsyncCommand::getId).toList();
        List<Long> idsB = store.findByToken("instance-b").stream().map(AsyncCommand::getId).toList();
        assertThat(idsA).doesNotContainAnyElementsOf(idsB);
    }

    /**
     * 验证悬挂回收：RUNNING 超时（回拨 updated_at 确定性构造"已死亡一个周期"）被打回 PENDING 并清令牌。
     */
    @Test
    void 悬挂回收_超时RUNNING回PENDING() throws Exception {
        JdbcAsyncCommandStore store = store("async_cmd_t4");
        AsyncCommand cmd = submit(store, "stale", "k1");
        store.claim("dead", 5, LocalDateTime.now());
        assertThat(store.countByStatus(AsyncCommand.RUNNING)).isEqualTo(1);

        // 确定性构造"超时"：claim 的 NOW() 是秒精度，同秒内 updated_at == NOW() 不会被判悬挂，
        // 直接把 updated_at 回拨到一分钟前（模拟执行器死亡后已经过了一个悬挂周期）
        rawJdbc().update("UPDATE async_cmd_t4 SET updated_at = NOW() - INTERVAL 1 MINUTE WHERE id = ?",
                cmd.getId());

        int reclaimed = store.reclaimStale(30, 10);
        assertThat(reclaimed).isEqualTo(1);
        assertThat(store.countByStatus(AsyncCommand.PENDING)).isEqualTo(1);
        assertThat(store.findByToken("dead")).isEmpty();
    }

    /**
     * 验证幂等计数口径：只统计 PENDING/RUNNING；落 SUCCESS 后归零。
     */
    @Test
    void 幂等计数_只统计未完成() throws Exception {
        JdbcAsyncCommandStore store = store("async_cmd_t5");
        AsyncCommand cmd = submit(store, "idem", "order-9");
        assertThat(store.countUnfinished("idem", "order-9")).isEqualTo(1);

        cmd.setStatus(AsyncCommand.SUCCESS);
        cmd.setClaimToken(null);
        store.updateState(cmd);
        assertThat(store.countUnfinished("idem", "order-9")).isZero();
    }

    /**
     * 验证延迟语义：nextRunAt 在未来的命令不被 claim 认领，也不计入到期积压。
     */
    @Test
    void 延迟未到期_不认领() throws Exception {
        JdbcAsyncCommandStore store = store("async_cmd_t6");
        AsyncCommand cmd = new AsyncCommand();
        cmd.setCmdType("delayed");
        cmd.setBizKey("k1");
        cmd.setPayload("{}");
        cmd.setStatus(AsyncCommand.PENDING);
        cmd.setMaxRetry(5);
        cmd.setRetryCount(0);
        cmd.setNextRunAt(LocalDateTime.now().plusHours(1));
        cmd.setOwner("it");
        store.insert(cmd);

        assertThat(store.claim("t", 10, LocalDateTime.now())).isZero();
        assertThat(store.countBacklogDue(LocalDateTime.now())).isZero();
    }

    /**
     * 验证管控分页：countPage 的可选过滤口径、selectPage 的 id 倒序与 offset 语义、翻页边界。
     */
    @Test
    void 管控分页_过滤与倒序() throws Exception {
        JdbcAsyncCommandStore store = store("async_cmd_t7");
        AsyncCommand a = submit(store, "page", "k1");
        AsyncCommand b = submit(store, "page", "k2");
        AsyncCommand c = submit(store, "other", "k3");
        assertThat(a.getId()).isPositive();
        assertThat(b.getId()).isPositive();
        assertThat(c.getId()).isPositive();

        updateTo(store, a, AsyncCommand.DEAD);
        updateTo(store, b, AsyncCommand.SUCCESS);
        updateTo(store, c, AsyncCommand.SUCCESS);
        // 逐步诊断：先确认每行落库状态，再查聚合（失败时能定位到具体行而非只看到 size 不符）
        assertThat(store.findById(a.getId()).getStatus()).isEqualTo(AsyncCommand.DEAD);
        assertThat(store.findById(b.getId()).getStatus()).isEqualTo(AsyncCommand.SUCCESS);
        assertThat(store.findById(c.getId()).getStatus()).isEqualTo(AsyncCommand.SUCCESS);

        assertThat(store.countPage(null, null)).isEqualTo(3);
        assertThat(store.countPage(AsyncCommand.DEAD, null)).isEqualTo(1);
        assertThat(store.countPage(AsyncCommand.SUCCESS, "page")).isEqualTo(1);
        assertThat(store.countPage(AsyncCommand.SUCCESS, null)).isEqualTo(2);
        // 注意：第 3 参是 offset 不是页码（页码→offset 的换算在 AdminService.page）。
        // 此前这里误传 1 当"第 1 页"，实际是跳过最新 1 行——CI 真库一跑即现形
        List<AsyncCommand> page = store.selectPage(AsyncCommand.SUCCESS, null, 0, 10);
        assertThat(page).hasSize(2);
        // id 倒序
        assertThat(page.get(0).getId()).isGreaterThan(page.get(1).getId());
        // 翻页边界：offset=1 + size=1 = 第二页第一条
        List<AsyncCommand> second = store.selectPage(AsyncCommand.SUCCESS, null, 1, 1);
        assertThat(second).hasSize(1);
        assertThat(second.get(0).getId()).isEqualTo(page.get(1).getId());
    }

    /**
     * 验证表名白名单：含 SQL 语句片段的表名被构造器直接拒绝（防注入）。
     */
    @Test
    void 非法表名_拒绝防注入() throws Exception {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> directStore("async; drop table x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("字母数字下划线");
    }

    /**
     * 把命令置为目标终态（清令牌）并回写存储。
     *
     * @param store  目标存储
     * @param cmd    待变更命令
     * @param status 目标状态
     * @return 影响行数
     */
    private int updateTo(JdbcAsyncCommandStore store, AsyncCommand cmd, String status) {
        cmd.setStatus(status);
        cmd.setClaimToken(null);
        return store.updateState(cmd);
    }
}
