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

import com.xp.starsite.lock.client.LockClient;
import com.xp.starsite.lock.model.LockCoreConfig;
import com.xp.starsite.lock.mybatis.ScheduleLockMapper;
import java.time.LocalDateTime;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DB 分布式锁实现（MyBatis Mapper 版，ShedLock 模式）：
 * 争锁是单条原子 UPDATE，TTL 过期自动释放，不阻塞未获锁实例。
 * <p>
 * 全部 SQL 落在 {@link ScheduleLockMapper} 的注解里——本类零硬编码 SQL。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class DbLockClient implements LockClient {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(DbLockClient.class);

    /** 表名白名单（防注入——${tableName} 是 MyBatis 字符串替换） */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    /** MyBatis Mapper */
    private final ScheduleLockMapper mapper;

    /** 锁表名 */
    private final String tableName;

    /** 持锁者标识 */
    private final String ownerId;

    /** 锁 TTL 毫秒 */
    private final long ttlMs;

    /**
     * 全参构造。
     *
     * @param mapper    MyBatis Mapper
     * @param tableName 锁表名（仅字母数字下划线）
     * @param ownerId   持锁者标识
     * @param ttlMs     锁 TTL 毫秒
     * @throws IllegalArgumentException 表名非法
     */
    public DbLockClient(ScheduleLockMapper mapper, String tableName, String ownerId, long ttlMs) {
        if (tableName == null || !SAFE_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("tableName 仅允许字母数字下划线: " + tableName);
        }
        this.mapper = mapper;
        this.tableName = tableName;
        this.ownerId = ownerId;
        this.ttlMs = ttlMs;
    }

    /**
     * 从配置构造。
     *
     * @param mapper ScheduleLockMapper
     * @param config 组件配置
     */
    public DbLockClient(ScheduleLockMapper mapper, LockCoreConfig config) {
        this(mapper, config.getTableName(), config.getOwnerId(), config.getTtlMs());
    }

    /**
     * 非阻塞争锁：先确保锁行存在（INSERT IGNORE 幂等），再原子 UPDATE 抢占。
     * 争锁 SQL 异常时 fail-open（返回 true 继续——宁可竞争不停摆，上层有幂等兜底）。
     *
     * @param lockName 锁名
     * @return true 获得锁；false 他方持锁中
     */
    @Override
    public boolean tryLock(String lockName) {
        try {
            // 首次争锁时先补种子行（INSERT IGNORE 已存在则无操作）
            mapper.seedLockRow(tableName, lockName);
            LocalDateTime until = LocalDateTime.now().plusNanos(ttlMs * 1_000_000);
            boolean acquired = mapper.tryAcquire(tableName, lockName, ownerId, until, java.time.LocalDateTime.now()) > 0;
            if (acquired) {
                log.trace("[starsite-lock] 获得锁: lock={}, owner={}", lockName, ownerId);
            }
            return acquired;
        } catch (RuntimeException e) {
            // 锁不可用不阻断调用方（fail-open——宁可多实例竞争也别停摆）
            log.warn("[starsite-lock] 争锁异常（fail-open 继续）: lock={}, err={}", lockName, e.getMessage());
            return true;
        }
    }

    /**
     * 释放锁（仅持锁者有效）。
     *
     * @param lockName 锁名
     */
    @Override
    public void unlock(String lockName) {
        try {
            int rows = mapper.release(tableName, lockName, ownerId);
            if (rows > 0) {
                log.info("[starsite-lock] 锁已释放: lock={}, owner={}", lockName, ownerId);
            }
        } catch (RuntimeException e) {
            log.warn("[starsite-lock] 释放锁异常（等 TTL 自动过期）: lock={}, err={}", lockName, e.getMessage());
        }
    }
}
