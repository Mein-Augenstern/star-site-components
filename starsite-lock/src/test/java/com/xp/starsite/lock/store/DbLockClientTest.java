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
     * 构造锁表就绪器（每客户端独立——实例内种子记忆互不共享，与生产多实例形态一致）。
     */
    private static com.xp.starsite.lock.store.LockTableProvisioner provisioner() throws Exception {
        return new com.xp.starsite.lock.store.LockTableProvisioner(
                mapper(), "starsite_lock", TEST_ID_SEQ::incrementAndGet);
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
        return new DbLockClient(mapper(), provisioner(), lockConfig.getTableName(),
                lockConfig.getOwnerId(), lockConfig.getTtlMs());
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

    /** 重入：嵌套两层 tryLock，内层 unlock 只递减计数——外层仍持锁，他方抢不走 */
    @Test
    void 重入_内层释放不误放外层() throws Exception {
        DbLockClient leader = lock("leader");
        DbLockClient follower = lock("follower");
        assertThat(leader.tryLock("reentrant-lock")).isTrue();
        assertThat(leader.tryLock("reentrant-lock")).isTrue(); // 重入 = 续期 + 层数 2

        leader.unlock("reentrant-lock"); // 内层退出：层数 2→1，DB 行不动
        assertThat(follower.tryLock("reentrant-lock")).isFalse(); // 外层仍持锁

        leader.unlock("reentrant-lock"); // 层数归零：真正释放
        assertThat(follower.tryLock("reentrant-lock")).isTrue();
    }

    /** 跨实例同 ownerId 共享存储端计数（分布式重入证明）：两个"Pod"各持客户端实例，
     *  同 ownerId 嵌套加锁——计数在锁行上全局一致，任一实例释放一层，归零才真正放锁 */
    @Test
    void 跨实例同ownerId_存储端共享重入计数() throws Exception {
        DbLockClient podA = new DbLockClient(mapper(), provisioner(), "starsite_lock",
                "same-owner", 30_000);
        DbLockClient podB = new DbLockClient(mapper(), provisioner(), "starsite_lock",
                "same-owner", 30_000);
        DbLockClient follower = lock("follower");

        assertThat(podA.tryLock("dist-lock")).isTrue();   // 实例A 首抢（count=1）
        assertThat(podB.tryLock("dist-lock")).isTrue();   // 实例B 同owner重入（count=2，存储端判定）

        podB.unlock("dist-lock");                          // B 释放一层（count=1，持有者不动）
        assertThat(follower.tryLock("dist-lock")).isFalse();

        podA.unlock("dist-lock");                          // 归零清持有者
        assertThat(follower.tryLock("dist-lock")).isTrue();
    }

    /** 行丢失自愈（分布式正确性）：外部删行后播种记忆陈旧，tryLock 仍能自愈重获——正确性不依赖 Pod 本地状态 */
    @Test
    void 行丢失自愈_播种记忆陈旧不阻断() throws Exception {
        DbLockClient lock = lock("healer");
        assertThat(lock.tryLock("heal-lock")).isTrue();
        lock.unlock("heal-lock");

        // 模拟外部删行（运维误删/表重建）——此刻实例内 seeded 记忆已陈旧
        try (Connection conn = dataSource.getConnection(); java.sql.Statement st = conn.createStatement()) {
            st.execute("DELETE FROM starsite_lock WHERE lock_name = 'heal-lock'");
        }

        // 修复前：seeded 陈旧 → 跳过播种 → UPDATE 0 行 → 永远 false（需重启才能恢复）
        assertThat(lock.tryLock("heal-lock")).isTrue();
    }

    /** 等待获取：他方持锁，等待窗口内异步释放——获得并返回（且未耗满窗口） */
    @Test
    void 等待获取_他方释放后获得() throws Exception {
        DbLockClient leader = lock("leader");
        DbLockClient follower = lock("follower");
        assertThat(leader.tryLock("wait-lock")).isTrue();

        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(200);   // 200ms 后释放（远小于 5s 等待窗口）
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
            leader.unlock("wait-lock");
        });
        releaser.start();
        long begin = System.currentTimeMillis();
        assertThat(follower.tryLock("wait-lock", 5_000)).isTrue();
        assertThat(System.currentTimeMillis() - begin).isLessThan(4_000); // 轮询及时感知释放
        releaser.join(1_000);
    }

    /** 等待获取：他方长持不放，窗口耗尽返回 false（等待时长真实生效） */
    @Test
    void 等待获取_窗口耗尽返回false() throws Exception {
        DbLockClient leader = lock("leader");
        DbLockClient follower = lock("follower");
        assertThat(leader.tryLock("hold-lock")).isTrue();   // TTL 30s，窗口内不会自动过期

        long begin = System.currentTimeMillis();
        assertThat(follower.tryLock("hold-lock", 300)).isFalse();
        assertThat(System.currentTimeMillis() - begin).isGreaterThanOrEqualTo(250); // 真等了
    }

    /** 多余释放（计数已空/从未持有）防御：不抛异常，直释放兜底无副作用 */
    @Test
    void 多余释放_防御不报错() throws Exception {
        DbLockClient lock = lock("leader");
        lock.unlock("never-locked"); // holds 无计数 → 直接 DB 释放（rows=0，静默）
        assertThat(lock.tryLock("fresh-lock")).isTrue(); // 防御路径不影响后续正常锁
    }

    /** 种子只插一次：同锁名多次 tryLock，seedLockRow SQL 仅首次执行（稳态 SQL 2→1） */
    @Test
    void 种子幂等_SQL只执行一次() throws Exception {
        ScheduleLockMapper real = mapper();
        java.util.concurrent.atomic.AtomicInteger seedCalls = new java.util.concurrent.atomic.AtomicInteger();
        ScheduleLockMapper counting = (ScheduleLockMapper) java.lang.reflect.Proxy.newProxyInstance(
                ScheduleLockMapper.class.getClassLoader(),
                new Class<?>[]{ScheduleLockMapper.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("seedLockRow")) {
                        seedCalls.incrementAndGet();
                    }
                    return method.invoke(real, args);
                });
        com.xp.starsite.lock.store.LockTableProvisioner countingProvisioner =
                new com.xp.starsite.lock.store.LockTableProvisioner(
                        counting, "starsite_lock", TEST_ID_SEQ::incrementAndGet);
        DbLockClient lock = new DbLockClient(counting, countingProvisioner, "starsite_lock",
                "sower", 30_000);

        assertThat(lock.tryLock("seed-once")).isTrue();
        assertThat(lock.tryLock("seed-once")).isTrue(); // 重入
        lock.unlock("seed-once");                        // 内层退出
        lock.unlock("seed-once");                        // 归零释放
        assertThat(lock.tryLock("seed-once")).isTrue();  // 重新争锁（不重播种）
        assertThat(seedCalls.get()).isEqualTo(1); // 三轮争锁只有首轮播种
    }

    /** 索引完备性：锁表必须有主键唯一索引 + lock_name 唯一索引（互斥权威与防注入锚点） */
    @Test
    void 索引完备性_PK与唯一键齐() throws Exception {
        // 标准 JDBC 元数据（不依赖 H2 方言视图）：H2 大写化表名
        int uniqueIndexKinds = 0;
        String lastName = null;
        try (var rs = dataSource.getConnection().getMetaData()
                .getIndexInfo(null, null, "STARSITE_LOCK", false, false)) {
            while (rs.next()) {
                if (!rs.getBoolean("NON_UNIQUE")) {
                    String idx = rs.getString("INDEX_NAME");
                    if (!idx.equals(lastName)) {   // 同一索引多列只计一次
                        uniqueIndexKinds++;
                        lastName = idx;
                    }
                }
            }
        }
        // PK(id) + uk_lock_name 两类唯一索引都必须在
        assertThat(uniqueIndexKinds).isEqualTo(2);
    }

    /** TTL 过期他方接管：持锁后推进假时钟越过 TTL，follower 抢占成功（崩溃实例最长霸锁一个 TTL） */
    @Test
    void TTL过期_他方接管() throws Exception {
        MutableClock clock = new MutableClock(java.time.Instant.now());
        DbLockClient leader = new DbLockClient(mapper(), provisioner(), "starsite_lock",
                "leader", 30_000, clock);
        DbLockClient follower = new DbLockClient(mapper(), provisioner(), "starsite_lock",
                "follower", 30_000, clock);

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
                    DbLockClient racer = new DbLockClient(mapper(), provisioner(), "starsite_lock",
                            owner, 30_000);
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
        com.xp.starsite.lock.store.LockTableProvisioner boomProvisioner =
                new com.xp.starsite.lock.store.LockTableProvisioner(
                        boom, "starsite_lock", TEST_ID_SEQ::incrementAndGet);
        DbLockClient lock = new DbLockClient(boom, boomProvisioner, "starsite_lock",
                "x", 30_000);
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
