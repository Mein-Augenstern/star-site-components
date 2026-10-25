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
     * 构造 Mapper（H2 + 手工 MyBatis 环境——不依赖 spring-boot mybatis-starter）。
     *
     * @return Mapper 代理（独立 SqlSession——SqlSession 非线程安全，每调用方一份）
     */
    private static ScheduleLockMapper mapper() throws Exception {
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
        return session.getMapper(ScheduleLockMapper.class);
    }

    /**
     * 构造 DbLockClient（系统时钟，默认 TTL）。
     *
     * @param ownerId 持锁者标识
     */
    private static DbLockClient lock(String ownerId) throws Exception {
        com.xp.starsite.lock.model.LockCoreConfig lockConfig =
                new com.xp.starsite.lock.model.LockCoreConfig();
        lockConfig.setOwnerId(ownerId);
        // 测试 id 生成器：AtomicLong 递增（固定值会跨用例主键冲突，fail-open 掩盖互斥语义）
        return new DbLockClient(mapper(), lockConfig, TEST_ID_SEQ::incrementAndGet);
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

    /** TTL 过期他方接管：持锁后推进假时钟越过 TTL，follower 抢占成功（崩溃实例最长霸锁一个 TTL） */
    @Test
    void TTL过期_他方接管() throws Exception {
        MutableClock clock = new MutableClock(java.time.Instant.now());
        DbLockClient leader = new DbLockClient(mapper(), "component_schedule_lock",
                "leader", 30_000, TEST_ID_SEQ::incrementAndGet, clock);
        DbLockClient follower = new DbLockClient(mapper(), "component_schedule_lock",
                "follower", 30_000, TEST_ID_SEQ::incrementAndGet, clock);

        assertThat(leader.tryLock("ttl-lock")).isTrue();
        assertThat(follower.tryLock("ttl-lock")).isFalse(); // TTL 内他方不可抢

        clock.advance(java.time.Duration.ofSeconds(31)); // 越过 30s TTL
        assertThat(follower.tryLock("ttl-lock")).isTrue(); // 过期自动让位
    }

    /** 并发争锁恰一胜：8 实例同起跑抢同一锁名，互斥语义下有且仅有一个成功 */
    @Test
    void 并发争锁_恰一胜() throws Exception {
        int threads = 8;
        java.util.concurrent.CountDownLatch startGate = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger winners = new java.util.concurrent.atomic.AtomicInteger();
        Thread[] pool = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            String owner = "racer-" + i;
            pool[i] = new Thread(() -> {
                try {
                    // 每线程独立 client（SqlSession 非线程安全，不跨线程共享）
                    DbLockClient racer = new DbLockClient(mapper(), "component_schedule_lock",
                            owner, 30_000, TEST_ID_SEQ::incrementAndGet);
                    startGate.await(); // 同起跑线：最大化竞争窗口
                    if (racer.tryLock("race-lock")) {
                        winners.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            pool[i].start();
        }
        startGate.countDown();
        for (Thread t : pool) {
            t.join(10_000);
        }
        assertThat(winners.get()).isEqualTo(1);
    }

    /** 锁存储故障 fail-open：争锁 SQL 异常返回 true（宁可竞争不停摆，上层幂等兜底） */
    @Test
    void 存储故障_failOpen继续() {
        ScheduleLockMapper boom = (ScheduleLockMapper) java.lang.reflect.Proxy.newProxyInstance(
                ScheduleLockMapper.class.getClassLoader(),
                new Class<?>[]{ScheduleLockMapper.class},
                (proxy, method, args) -> { throw new IllegalStateException("db down"); });
        DbLockClient lock = new DbLockClient(boom, "component_schedule_lock",
                "x", 30_000, TEST_ID_SEQ::incrementAndGet);
        assertThat(lock.tryLock("any-lock")).isTrue();
    }

    /**
     * 可推进假时钟：TTL 到期判定的确定性构造（免真实等待）。
     */
    static final class MutableClock extends java.time.Clock {

        /** 当前虚拟时刻（volatile：测试线程推进，被测线程读） */
        private volatile java.time.Instant instant;

        /**
         * 从指定时刻起走。
         *
         * @param start 起始时刻
         */
        MutableClock(java.time.Instant start) {
            this.instant = start;
        }

        /**
         * 推进虚拟时钟。
         *
         * @param d 推进量
         */
        void advance(java.time.Duration d) {
            instant = instant.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneId.systemDefault();
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return instant;
        }
    }
}
