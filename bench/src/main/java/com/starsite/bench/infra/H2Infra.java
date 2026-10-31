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
package com.starsite.bench.infra;

import com.xp.starsite.asynccmd.mybatis.AsyncCommandMapper;
import com.xp.starsite.lock.mybatis.ScheduleLockMapper;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/**
 * H2 内存库基础设施（MySQL 兼容模式）——bench 的默认存储后端。
 * 提供 DataSource + MyBatis Mapper 代理，支持 lock 和 async-command 的完整链路。
 *
 * @author muyi
 */
public final class H2Infra {

    private H2Infra() {}

    /**
     * 创建 H2 内存数据源（MySQL 兼容模式）。
     */
    public static DataSource dataSource() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:bench;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        return ds;
    }

    /**
     * 创建 MyBatis SqlSessionFactory（注册 lock + async 两个 Mapper）。
     */
    public static SqlSessionFactory sqlSessionFactory(DataSource ds) {
        Configuration config = new Configuration();
        config.setEnvironment(new Environment("bench", new JdbcTransactionFactory(), ds));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(ScheduleLockMapper.class);
        config.addMapper(AsyncCommandMapper.class);
        return new SqlSessionFactoryBuilder().build(config);
    }

    /**
     * 建锁表（含 hold_count 列）。
     */
    public static void createLockTable(DataSource ds) throws Exception {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
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
     * 建命令表（含索引三件套）。
     */
    public static void createAsyncTable(DataSource ds) throws Exception {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS async_command ("
                    + "id BIGINT NOT NULL PRIMARY KEY, "
                    + "cmd_type VARCHAR(64) NOT NULL, "
                    + "biz_key VARCHAR(128) DEFAULT '', "
                    + "payload TEXT, "
                    + "status VARCHAR(16) NOT NULL DEFAULT 'PENDING', "
                    + "max_retry INT NOT NULL DEFAULT 5, "
                    + "retry_count INT NOT NULL DEFAULT 0, "
                    + "next_run_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "last_error VARCHAR(500), "
                    + "owner VARCHAR(64) DEFAULT '', "
                    + "claim_token VARCHAR(64), "
                    + "gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    /**
     * 创建 ScheduleLockMapper 代理。
     */
    public static ScheduleLockMapper lockMapper(SqlSessionFactory factory) {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(ScheduleLockMapper.class);
        }
    }

    /**
     * 创建 AsyncCommandMapper 代理。
     */
    public static AsyncCommandMapper asyncMapper(SqlSessionFactory factory) {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(AsyncCommandMapper.class);
        }
    }
}
