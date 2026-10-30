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
package com.xp.starsite.lock.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.xp.starsite.lock.client.LockAdminClient;
import com.xp.starsite.lock.mybatis.ScheduleLockMapper;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * 锁运维门面测试：强制释放/查持有者/列持锁/快照——与正式互斥语义隔离的侧门。
 *
 * @author muyi
 * @date 2026/10/30
 */
class DbLockAdminClientTest {

    /** H2 数据源 */
    private static javax.sql.DataSource dataSource;

    /** 测试 id 发号 */
    private static final AtomicLong TEST_ID_SEQ = new AtomicLong(0);

    /**
     * 建库建表（含 hold_count 列）。
     */
    @BeforeAll
    static void setup() throws Exception {
        dataSource = new EmbeddedDatabaseBuilder()
                .setName("lock-admin-test")   // 显式隔离：多测试类共享 JVM 时 H2 默认同名会互相污染
                .setType(EmbeddedDatabaseType.H2)
                .build();
        try (Connection conn = dataSource.getConnection(); java.sql.Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS starsite_lock ("
                    + "id BIGINT NOT NULL PRIMARY KEY, "
                    + "lock_name VARCHAR(64) NOT NULL, "
                    + "locked_by VARCHAR(128), "
                    + "locked_until TIMESTAMP, "
                    + "hold_count INT NOT NULL DEFAULT 0, "
                    + "gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "CONSTRAINT uk_lock_name UNIQUE (lock_name))");
        }
    }

    /**
     * 构造 Mapper（H2 + 手工 MyBatis）。
     */
    private static ScheduleLockMapper mapper() throws Exception {
        org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        config.addMapper(ScheduleLockMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(config);
        SqlSession session = factory.openSession(true);
        return session.getMapper(ScheduleLockMapper.class);
    }

    /** 强制释放：无视持有者与计数，清空全部持有字段 */
    @Test
    void 强制释放_清空持有字段() throws Exception {
        ScheduleLockMapper mapper = mapper();
        mapper.seedLockRow("starsite_lock", TEST_ID_SEQ.incrementAndGet(), "admin-test-lock");
        mapper.tryAcquire("starsite_lock", "admin-test-lock", "victim",
                java.time.LocalDateTime.now().plusSeconds(30), java.time.LocalDateTime.now());

        LockAdminClient admin = new DbLockAdminClient(mapper, "starsite_lock");
        assertThat(admin.findHolder("admin-test-lock")).isEqualTo("victim");

        assertThat(admin.forceRelease("admin-test-lock")).isTrue();
        assertThat(admin.findHolder("admin-test-lock")).isNull();
        assertThat(admin.getLockInfo("admin-test-lock").holdCount()).isEqualTo(0);
    }

    /** 列出全部持锁：只返回 locked_by 非空的行 */
    @Test
    void 列出全部持锁_仅含非空() throws Exception {
        ScheduleLockMapper mapper = mapper();
        LockAdminClient admin = new DbLockAdminClient(mapper, "starsite_lock");

        mapper.seedLockRow("starsite_lock", TEST_ID_SEQ.incrementAndGet(), "list-a");
        mapper.seedLockRow("starsite_lock", TEST_ID_SEQ.incrementAndGet(), "list-b");
        mapper.tryAcquire("starsite_lock", "list-a", "holder-1",
                java.time.LocalDateTime.now().plusSeconds(30), java.time.LocalDateTime.now());
        // list-b 播种但未争锁——不应出现在持锁列表

        var held = admin.listHeldLocks();
        assertThat(held).anySatisfy(info -> {
            assertThat(info.lockName()).isEqualTo("list-a");
            assertThat(info.ownerId()).isEqualTo("holder-1");
        });
        assertThat(held).noneSatisfy(info -> assertThat(info.lockName()).isEqualTo("list-b"));
    }

    /** 快照：行不存在返回 null；行在但空返回 holdCount=0 */
    @Test
    void 快照_行不存在null_行在空holdCount0() throws Exception {
        ScheduleLockMapper mapper = mapper();
        LockAdminClient admin = new DbLockAdminClient(mapper, "starsite_lock");

        assertThat(admin.getLockInfo("no-such-lock")).isNull();

        mapper.seedLockRow("starsite_lock", TEST_ID_SEQ.incrementAndGet(), "empty-lock");
        LockAdminClient.LockInfo info = admin.getLockInfo("empty-lock");
        assertThat(info).isNotNull();
        assertThat(info.holdCount()).isEqualTo(0);
        assertThat(info.ownerId()).isNull();
    }
}
