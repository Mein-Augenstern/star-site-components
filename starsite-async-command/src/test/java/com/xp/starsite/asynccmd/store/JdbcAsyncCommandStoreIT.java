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
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * JDBC 存储集成测试（Testcontainers 真库）：建表/提交/令牌认领/悬挂回收/幂等/分页。
 *
 * @author muyi
 * @since 2026-10
 */
@Testcontainers
class JdbcAsyncCommandStoreIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("starsite")
            .withUsername("test")
            .withPassword("test");

    private JdbcAsyncCommandStore store(String table) throws java.sql.SQLException {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriver(new com.mysql.cj.jdbc.Driver());
        ds.setUrl(MYSQL.getJdbcUrl());
        ds.setUsername(MYSQL.getUsername());
        ds.setPassword(MYSQL.getPassword());
        return new JdbcAsyncCommandStore(new JdbcTemplate(ds), table, true);
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
        assertThat(cmd.getId()).isPositive();
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
        submit(store, "stale", "k1");
        store.claim("dead", 5, LocalDateTime.now());
        assertThat(store.countByStatus(AsyncCommand.RUNNING)).isEqualTo(1);

        int reclaimed = store.reclaimStale(0, 10); // 立即视为悬挂
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
        a.setStatus(AsyncCommand.DEAD); a.setClaimToken(null); store.updateState(a);
        b.setStatus(AsyncCommand.SUCCESS); b.setClaimToken(null); store.updateState(b);
        c.setStatus(AsyncCommand.SUCCESS); c.setClaimToken(null); store.updateState(c);

        assertThat(store.countPage(null, null)).isEqualTo(3);
        assertThat(store.countPage(AsyncCommand.DEAD, null)).isEqualTo(1);
        assertThat(store.countPage(AsyncCommand.SUCCESS, "page")).isEqualTo(1);
        List<AsyncCommand> page = store.selectPage(AsyncCommand.SUCCESS, null, 1, 10);
        assertThat(page).hasSize(2);
        assertThat(page.get(0).getId()).isGreaterThan(page.get(1).getId()); // id 倒序
    }

    @Test
    void 非法表名_拒绝防注入() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> store("async; drop table x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("字母数字下划线");
    }
}
