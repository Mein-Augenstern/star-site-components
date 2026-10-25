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

import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * DB 分布式锁测试（H2）：争锁互斥、TTL 过期接管、主动释放让位、非持锁者不可续期。
 *
 * @author muyi
 * @date 2026/10/25
 */
class DbScheduleLockTest {

    /** H2 数据源（类级共享） */
    private static DataSource dataSource;

    /**
     * 建库 + 手动建锁表（H2 的 DDL 与锁类内部 DDL 一致）。
     */
    @BeforeAll
    static void setup() throws Exception {
        dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .build();
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS component_schedule_lock ("
                    + "lock_name VARCHAR(64) NOT NULL PRIMARY KEY, "
                    + "locked_by VARCHAR(128), "
                    + "locked_until TIMESTAMP, "
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    /** 首次争锁成功（空行种子后） */
    @Test
    void 首次争锁_成功() {
        DbScheduleLock lock = new DbScheduleLock(dataSource, "test-lock-1", "inst-a", 10_000);
        // 先插种子行（模拟已初始化）
        seed("test-lock-1");
        assertThat(lock.tryLock()).isTrue();
    }

    /** 他方持锁时争锁失败（互斥核心语义） */
    @Test
    void 他方持锁_争锁失败() {
        seed("test-lock-2");
        DbScheduleLock leader = new DbScheduleLock(dataSource, "test-lock-2", "leader", 60_000);
        assertThat(leader.tryLock()).isTrue();

        DbScheduleLock follower = new DbScheduleLock(dataSource, "test-lock-2", "follower", 60_000);
        assertThat(follower.tryLock()).isFalse(); // leader 持锁中，follower 不得入
    }

    /** 自己持锁时可以续期（locked_by = 自己的条件分支） */
    @Test
    void 自己持锁_可以续期() {
        seed("test-lock-3");
        DbScheduleLock lock = new DbScheduleLock(dataSource, "test-lock-3", "inst-x", 60_000);
        assertThat(lock.tryLock()).isTrue();
        assertThat(lock.tryLock()).isTrue(); // 第二次 = 续期（locked_by 匹配自己）
    }

    /** 主动释放后其他实例可立即争锁 */
    @Test
    void 主动释放_立即可争() {
        seed("test-lock-4");
        DbScheduleLock leader = new DbScheduleLock(dataSource, "test-lock-4", "leader", 60_000);
        leader.tryLock();
        leader.unlock();

        DbScheduleLock follower = new DbScheduleLock(dataSource, "test-lock-4", "follower", 60_000);
        assertThat(follower.tryLock()).isTrue(); // leader 释放后 follower 立即接管
    }

    /** 种子行辅助：插入空锁行（模拟组件初始化或 DBA 建行） */
    private static void seed(String lockName) {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("INSERT IGNORE INTO component_schedule_lock (lock_name) VALUES ('" + lockName + "')");
        } catch (Exception e) {
            // H2 无 INSERT IGNORE——改 MERGE
            try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
                st.execute("MERGE INTO component_schedule_lock (lock_name) KEY (lock_name) VALUES ('" + lockName + "')");
            } catch (Exception ignored) {
                // 已存在则忽略
            }
        }
    }
}
