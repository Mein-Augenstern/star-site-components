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

import com.xp.starsite.lock.mybatis.ScheduleLockMapper;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * DB 锁实现全链路测试（H2 内存库）：争锁互斥/自续期/主动释放让位/fail-open。
 *
 * @author muyi
 * @date 2026/10/25
 */
class DbLockClientTest {

    /** H2 数据源（类级共享，各用例独立锁名隔离） */
    private static javax.sql.DataSource dataSource;

    /** 测试 id 生成序列（各用例的种子行主键互不冲突） */
    private static final java.util.concurrent.atomic.AtomicLong TEST_ID_SEQ =
            new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * 建库 + 建锁表。
     */
    @BeforeAll
    static void setup() throws Exception {
        dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .build();
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS component_schedule_lock ("
                    + "id BIGINT NOT NULL PRIMARY KEY, "
                    + "lock_name VARCHAR(64) NOT NULL, "
                    + "locked_by VARCHAR(128), "
                    + "locked_until TIMESTAMP, "
                    + "gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "CONSTRAINT uk_lock_name UNIQUE (lock_name))");
        }
    }

    /**
     * 构造 Mapper + DbLockClient（H2 + 手工 MyBatis 环境——不依赖 spring-boot mybatis-starter）。
     */
    private static DbLockClient lock(String ownerId) throws Exception {
        // H2 + MyBatis 手工装配（Mapper 代理直建，不走 Spring）
        org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory txFactory =
                new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory();
        org.apache.ibatis.mapping.Environment env =
                new org.apache.ibatis.mapping.Environment("test", txFactory, dataSource);
        org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
        config.setEnvironment(env);
        config.addMapper(ScheduleLockMapper.class);
        org.apache.ibatis.session.SqlSessionFactory factory =
                new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(config);
        org.apache.ibatis.session.SqlSession session = factory.openSession(true);
        ScheduleLockMapper mapper = session.getMapper(ScheduleLockMapper.class);
        com.xp.starsite.lock.model.LockCoreConfig lockConfig =
                new com.xp.starsite.lock.model.LockCoreConfig();
        lockConfig.setOwnerId(ownerId);
        // 测试 id 生成器：AtomicLong 递增（固定值会跨用例主键冲突，fail-open 掩盖互斥语义）
        return new DbLockClient(mapper, lockConfig, TEST_ID_SEQ::incrementAndGet);
    }

    /** 首次争锁成功 */
    @Test
    void 首次争锁_成功() throws Exception {
        DbLockClient lock = lock("inst-a");
        assertThat(lock.tryLock("first-lock")).isTrue();
    }

    /** 他方持锁时争锁失败（互斥核心语义） */
    @Test
    void 他方持锁_争锁失败() throws Exception {
        DbLockClient leader = lock("leader");
        DbLockClient follower = lock("follower");
        assertThat(leader.tryLock("mutex-lock")).isTrue();
        assertThat(follower.tryLock("mutex-lock")).isFalse();
    }

    /** 自己持锁时可以续期（TTL 刷新） */
    @Test
    void 自己持锁_可以续期() throws Exception {
        DbLockClient lock = lock("inst-x");
        assertThat(lock.tryLock("renew-lock")).isTrue();
        assertThat(lock.tryLock("renew-lock")).isTrue(); // 第二次 = 续期
    }

    /** 主动释放后其他实例可立即争锁 */
    @Test
    void 主动释放_立即可争() throws Exception {
        DbLockClient leader = lock("leader");
        DbLockClient follower = lock("follower");
        leader.tryLock("release-lock");
        leader.unlock("release-lock");
        assertThat(follower.tryLock("release-lock")).isTrue();
    }

    /** 多锁名独立（不同锁名互不影响） */
    @Test
    void 多锁名_互不影响() throws Exception {
        DbLockClient lock = lock("multi");
        assertThat(lock.tryLock("lock-a")).isTrue();
        assertThat(lock.tryLock("lock-b")).isTrue(); // 不同锁名可同时持有
    }
}
