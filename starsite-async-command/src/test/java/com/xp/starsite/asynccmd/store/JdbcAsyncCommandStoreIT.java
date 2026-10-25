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

import com.xp.starsite.asynccmd.AsyncCommand;
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
 * @since 2026-10
 */
class JdbcAsyncCommandStoreIT {

    private static final String EXT_URL = System.getenv("TEST_MYSQL_URL");

    private static MySQLContainer<?> container;

    @BeforeAll
    static void startDb() {
        if (EXT_URL == null) {
            container = new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("starsite").withUsername("test").withPassword("test");
            container.start();
        }
    }

    @AfterAll
    static void stopDb() {
        if (container != null) {
            container.stop();
        }
    }

    private static String jdbcUrl() {
        return EXT_URL != null ? EXT_URL : container.getJdbcUrl();
    }

    private static String dbUser() {
        return EXT_URL != null ? env("TEST_MYSQL_USER", "root") : container.getUsername();
    }

    private static String dbPassword() {
        return EXT_URL != null ? env("TEST_MYSQL_PASSWORD", "root") : container.getPassword();
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null ? def : v;
    }

    private JdbcAsyncCommandStore store(String table) throws SQLException {
        // 外部库模式下表会跨运行残留（testcontainers 每次新容器无此问题），先落为敬
        rawJdbc().execute("DROP TABLE IF EXISTS " + table);
        return directStore(table);
    }

    /** 无自清理的直构通道（非法表名用例专用：不能把非法名拼进任何要执行的 SQL） */
    private JdbcAsyncCommandStore directStore(String table) throws SQLException {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriver(new com.mysql.cj.jdbc.Driver());
        ds.setUrl(jdbcUrl());
        ds.setUsername(dbUser());
        ds.setPassword(dbPassword());
        return new JdbcAsyncCommandStore(new JdbcTemplate(ds), table, true);
    }

    /** 测试内直连 SQL 通道（回拨 updated_at 等确定性构造用） */
    private JdbcTemplate rawJdbc() throws SQLException {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriver(new com.mysql.cj.jdbc.Driver());
        ds.setUrl(jdbcUrl());
        ds.setUsername(dbUser());
        ds.setPassword(dbPassword());
        return new JdbcTemplate(ds);
    }

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

    @Test
    void 建表与提交_自增id回填() throws Exception {
        JdbcAsyncCommandStore store = store("async_cmd_t1");
        AsyncCommand cmd = submit(store, "create", "k1");
        assertThat(cmd.getId()).as("insert 后 id 必须回填").isPositive();
        assertThat(store.findById(cmd.getId())).isNotNull();
        assertThat(store.findById(cmd.getId()).getCmdType()).isEqualTo("create");
    }

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
        assertThat(page.get(0).getId()).isGreaterThan(page.get(1).getId()); // id 倒序
        // 翻页边界：offset=1 + size=1 = 第二页第一条
        List<AsyncCommand> second = store.selectPage(AsyncCommand.SUCCESS, null, 1, 1);
        assertThat(second).hasSize(1);
        assertThat(second.get(0).getId()).isEqualTo(page.get(1).getId());
    }

    @Test
    void 非法表名_拒绝防注入() throws Exception {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> directStore("async; drop table x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("字母数字下划线");
    }

    private int updateTo(JdbcAsyncCommandStore store, AsyncCommand cmd, String status) {
        cmd.setStatus(status);
        cmd.setClaimToken(null);
        return store.updateState(cmd);
    }
}
