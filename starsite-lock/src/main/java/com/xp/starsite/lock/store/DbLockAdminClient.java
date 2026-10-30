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

import com.xp.starsite.lock.client.LockAdminClient;
import com.xp.starsite.lock.mybatis.ScheduleLockMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DB 锁运维实现（运维侧门职责）：强制释放卡死锁、查持有者、列全部持锁。
 * <p>
 * 与 {@link DbLockClient} 的互斥语义完全隔离——本类不做争锁/重入/自愈，
 * 只做运维操作；装配由 {@code starsite.lock.admin-enabled=true} 独立开关控制。
 *
 * @author muyi
 * @date 2026/10/30
 */
public class DbLockAdminClient implements LockAdminClient {

    /** 日志（强制释放属高危操作，info 级可见） */
    private static final Logger log = LoggerFactory.getLogger(DbLockAdminClient.class);

    /** MyBatis Mapper（运维 SQL 全在 Mapper XML） */
    private final ScheduleLockMapper mapper;

    /** 锁表名（已过白名单校验） */
    private final String tableName;

    /**
     * 构造运维客户端。
     *
     * @param mapper    MyBatis Mapper
     * @param tableName 锁表名（仅字母数字下划线）
     * @throws IllegalArgumentException 表名非法
     */
    public DbLockAdminClient(ScheduleLockMapper mapper, String tableName) {
        if (tableName == null || !tableName.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("tableName 仅允许字母数字下划线: " + tableName);
        }
        this.mapper = mapper;
        this.tableName = tableName;
    }

    /**
     * 强制释放锁：清空全部持有字段，无视持有者与计数。
     *
     * @param lockName 锁名
     * @return true 行存在且已清空；false 行不存在
     */
    @Override
    public boolean forceRelease(String lockName) {
        int rows = mapper.forceRelease(tableName, lockName);
        if (rows > 0) {
            log.info("[starsite-lock-admin] 强制释放锁: lock={}（运维操作，无视持有者）", lockName);
        }
        return rows > 0;
    }

    /**
     * 查询锁的当前持有者。
     *
     * @param lockName 锁名
     * @return 持有者 ownerId；无人持有或行不存在返回 null
     */
    @Override
    public String findHolder(String lockName) {
        return mapper.findHolder(tableName, lockName);
    }

    /**
     * 列出当前全部持锁（locked_by 非空的行）。
     *
     * @return 持锁信息列表
     */
    @Override
    public List<LockInfo> listHeldLocks() {
        return mapper.selectHeldLocks(tableName);
    }

    /**
     * 单锁状态快照（含未持有行——诊断需要看到"行在但空"）。
     *
     * @param lockName 锁名
     * @return 锁信息；行不存在返回 null
     */
    @Override
    public LockInfo getLockInfo(String lockName) {
        return mapper.selectLockInfo(tableName, lockName);
    }
}
