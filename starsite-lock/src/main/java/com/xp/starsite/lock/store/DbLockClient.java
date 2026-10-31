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
import com.xp.starsite.lock.mybatis.ScheduleLockMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DB 分布式锁实现（互斥语义职责，ShedLock 模式）：
 * 争锁是单条原子 UPDATE（lock_name 唯一键点查），重入计数在锁行 hold_count 列——
 * 存储端权威（分布式写法：同 ownerId 的任意实例共享同一份计数），TTL 过期自动释放。
 * <p>
 * 职责边界（单一职责拆分）：
 * <ul>
 *   <li>本类——互斥语义：CAS 争锁/续期、按计数释放；争锁 SQL 异常 fail-open</li>
 *   <li>{@link LockTableProvisioner}——存储就绪：建表与锁行播种（种子 SQL 每锁名一次）</li>
 *   <li>重入计数——锁行 hold_count 列（存储端权威，同 ownerId 跨实例共享一致）</li>
 *   <li>starter——装配：ownerId 回填、模式选择、建表触发</li>
 * </ul>
 * SQL 全在与 Mapper 同包的 XML——本类零硬编码 SQL；索引最小集 PK + uk(lock_name)，全点查无扫描。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class DbLockClient implements LockClient {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(DbLockClient.class);

    /** 表名白名单（防注入——${tableName} 是 MyBatis 字符串替换；刻意跨模块复制，勿抽公共模块） */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    /** MyBatis Mapper */
    private final ScheduleLockMapper mapper;

    /** 存储就绪器（建表/播种，争锁路径按需播种） */
    private final LockTableProvisioner provisioner;

    /** 锁表名 */
    private final String tableName;

    /** 持锁者标识 */
    private final String ownerId;

    /** 锁 TTL 毫秒 */
    private final long ttlMs;

    /** 时钟（TTL 到期判定；测试注入假时钟确定性构造过期场景） */
    private final Clock clock;

    /**
     * 构造（系统时钟）。
     *
     * @param mapper      MyBatis Mapper
     * @param provisioner 存储就绪器（须与本客户端同表名）
     * @param tableName   锁表名（仅字母数字下划线）
     * @param ownerId     持锁者标识
     * @param ttlMs       锁 TTL 毫秒
     * @throws IllegalArgumentException 表名非法
     */
    public DbLockClient(ScheduleLockMapper mapper, LockTableProvisioner provisioner,
                        String tableName, String ownerId, long ttlMs) {
        this(mapper, provisioner, tableName, ownerId, ttlMs, Clock.systemDefaultZone());
    }

    /**
     * 全参构造（显式时钟——测试注入假时钟确定性构造过期场景）。
     *
     * @param mapper      MyBatis Mapper
     * @param provisioner 存储就绪器（须与本客户端同表名）
     * @param tableName   锁表名（仅字母数字下划线）
     * @param ownerId     持锁者标识
     * @param ttlMs       锁 TTL 毫秒
     * @param clock       时钟
     * @throws IllegalArgumentException 表名非法
     */
    public DbLockClient(ScheduleLockMapper mapper, LockTableProvisioner provisioner,
                        String tableName, String ownerId, long ttlMs, Clock clock) {
        if (tableName == null || !SAFE_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("tableName 仅允许字母数字下划线: " + tableName);
        }
        this.mapper = mapper;
        this.provisioner = provisioner;
        this.tableName = tableName;
        this.ownerId = ownerId;
        this.ttlMs = ttlMs;
        this.clock = clock;
    }

    /**
     * 非阻塞争锁（可重入，分布式安全）：播种（性能提示级缓存）→ 单条 UPDATE 原子抢占
     * （locked_by 为空/已过期/是自己三种命中）；未抢到时校验锁行存活性——
     * 行丢失（外部删行/表重建导致播种记忆陈旧）自动重播种并重试一次，无需重启自愈。
     * 成功或重入在存储端递增 hold_count；争锁 SQL 异常 fail-open。
     * <p>
     * 正确性不依赖任何 Pod 本地状态：播种缓存只影响 SQL 条数（持有路径 1 条，
     * 竞争路径 2 条），行丢失经"findHolder 校验 + 重播种 + 重试"收敛——
     * 多 Pod 并发自愈时重播种幂等、重试仍走原子 UPDATE，互斥不破。
     * <p>
     * 重入粒度为 ownerId：同 ownerId 的任意实例（含跨 Pod 嵌套调用）共享锁行上的
     * 同一份计数——嵌套作用域的内层 unlock 只递减，归零才清持有者。
     *
     * @param lockName 锁名
     * @return true 获得锁（或重入成功）；false 他方持锁中
     */
    @Override
    public boolean tryLock(String lockName) {
        try {
            provisioner.seedIfNeeded(lockName);
            if (acquireOnce(lockName)) {
                return true;
            }
            // 未抢到：先问存储就绪器是否行丢失（自愈编排是供给职责，互斥语义不掺和）——
            // 已自愈则重试一次，行健在即他方持有
            return provisioner.healIfRowLost(lockName) && acquireOnce(lockName);
        } catch (RuntimeException e) {
            // 锁不可用不阻断调用方（fail-open——宁可多实例竞争也别停摆）
            log.warn("[starsite-lock] 争锁异常（fail-open 继续）: lock={}, err={}", lockName, e.getMessage());
            return true;
        }
    }

    /**
     * 单次原子抢占（含取钟；重入计数由 SQL 的 CASE 在存储端递增）。
     *
     * @param lockName 锁名
     * @return true 抢到或重入（锁行 hold_count 已递增）
     */
    private boolean acquireOnce(String lockName) {
        // 单次取钟：until 与 now 同基准（两次 now() 会有毫秒级漂移，到期判定失真）
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime until = now.plusNanos(ttlMs * 1_000_000);
        return mapper.tryAcquire(tableName, lockName,
                ownerId, until, now) > 0;
    }

    /**
     * 释放一层持有（可重入，存储端原子）：单条 UPDATE——hold_count &gt; 1 时仅递减
     * （外层仍在临界区，持有者字段不动）；归零清空持有者（任何实例可立即争锁）。
     * 作用于锁行上的共享计数：同 ownerId 跨实例的嵌套 unlock 同样只递减各自的层。
     *
     * @param lockName 锁名
     */
    @Override
    public void unlock(String lockName) {
        try {
            int rows = mapper.release(tableName, lockName, ownerId);
            if (rows > 0) {
                log.info("[starsite-lock] 释放一层持有: lock={}, owner={}", lockName, ownerId);
            }
        } catch (RuntimeException e) {
            log.warn("[starsite-lock] 释放锁异常（等 TTL 自动过期）: lock={}, err={}", lockName, e.getMessage());
        }
    }
}
