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
package com.xp.starsite.schedule.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xp.starsite.schedule.api.RunStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * JDBC 存储真库 IT（Testcontainers mysql:8.0；TEST_MYSQL_URL 外部库逃生口——与
 * async-command IT 同形）。验证：建表 / start-finish 往返 / SKIPPED / 悬挂回收 /
 * 列表分列 / lastDone / 表名白名单。
 *
 * @author muyi
 * @date 2026/11/02
 */
class JdbcRunLogStoreIT {

    /** 外部库直连地址（无 docker 环境的逃生口） */
    private static final String EXT_URL = System.getenv("TEST_MYSQL_URL");

    /** 一次性 MySQL 容器 */
    private static MySQLContainer<?> container;

    /** 被测存储 */
    private static JdbcRunLogStore store;

    /** 测试 id 发号（sequence 组件的替身——真库只关心 BIGINT 唯一性） */
    private static final AtomicLong IDS = new AtomicLong(1000);

    @BeforeAll
    static void startDb() {
        if (EXT_URL == null) {
            container = new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("starsite").withUsername("test").withPassword("test");
            container.start();
        }
        String url = EXT_URL != null ? EXT_URL : container.getJdbcUrl();
        String user = EXT_URL != null ? System.getenv().getOrDefault("TEST_MYSQL_USER", "root") : "test";
        String pass = EXT_URL != null ? System.getenv().getOrDefault("TEST_MYSQL_PASSWORD", "root") : "test";
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(com.mysql.cj.jdbc.Driver.class);
        ds.setUrl(url);
        ds.setUsername(user);
        ds.setPassword(pass);
        store = new JdbcRunLogStore(new JdbcTemplate(ds), "schedule_run_log",
                new ScheduleRunLogSql.MySql(), true, IDS::incrementAndGet);
    }

    @AfterAll
    static void stopDb() {
        if (container != null) {
            container.stop();
        }
    }

    private static LocalDateTime t(int hour) {
        return LocalDateTime.of(2026, 11, 2, hour, 0, 0);
    }

    @Test
    void startFinishRoundTrip() {
        var log = store.start("it1", "sch:it1", t(8), t(8), "host:1");
        assertThat(log.getId()).isPositive();
        assertThat(log.getStatus()).isEqualTo(RunStatus.RUNNING);

        store.finish(log.getId(), RunStatus.DONE, 99L, null);
        var done = store.findById(log.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(RunStatus.DONE);
        assertThat(done.getDurationMs()).isEqualTo(99L);
        assertThat(done.getFinishedAt()).isNotNull();
    }

    @Test
    void skipRowAndListColumns() {
        var skipped = store.skip("it2", "sch:it2", t(9), t(9), "host:2");
        assertThat(skipped.getStatus()).isEqualTo(RunStatus.SKIPPED_LOCKED);

        List<com.xp.starsite.schedule.api.RunLog> list = store.recentByTask("it2", 10);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getError()).isNull();  // 列表无 error（D6）
    }

    @Test
    void failureDetailCarriesError() {
        var log = store.start("it3", "sch:it3", t(10), t(10), "host:3");
        store.finish(log.getId(), RunStatus.FAILED, 5L, "IllegalStateException: boom");
        assertThat(store.findById(log.getId()).orElseThrow().getError()).contains("boom");
    }

    @Test
    void lastDoneAndStaleReclaim() {
        var a = store.start("it4", "sch:it4", t(1), t(1), "h:1");
        store.finish(a.getId(), RunStatus.DONE, 1L, null);
        assertThat(store.lastDoneTriggerTime("it4")).contains(t(1));

        var stale = store.start("it4", "sch:it4", t(2), t(2), "h:1");  // 遗留 RUNNING
        int marked = store.markStaleRunning(t(9));
        assertThat(marked).isGreaterThanOrEqualTo(1);
        assertThat(store.findById(stale.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.STALE);
        // DONE 不被误标
        assertThat(store.findById(a.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.DONE);
    }

    @Test
    void tableNameWhitelist() {
        assertThatThrownBy(() -> new JdbcRunLogStore(null, "bad-name;drop", null, false, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
