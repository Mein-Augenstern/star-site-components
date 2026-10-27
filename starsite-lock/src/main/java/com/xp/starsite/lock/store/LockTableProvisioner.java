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

import com.xp.starsite.lock.mybatis.ScheduleLockMapper;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 锁表就绪器（存储供给职责，与 {@link DbLockClient} 的互斥语义分离）：
 * 负责建表（幂等 IF NOT EXISTS）与锁行播种（每锁名实例内只插一次）。
 * <p>
 * 播种用 INSERT ... WHERE NOT EXISTS 只插不覆盖——并发首抢多实例同时播种，
 * 输家撞 lock_name 唯一键属正常并发结局（行已存在正是期望终态），吞掉即可；
 * 互斥权威始终在 DbLockClient 的单条 UPDATE，播种不参与判定。
 * <p>
 * 性能：种子 SQL 每锁名只执行一次，稳态下争锁只剩 1 条点查 UPDATE。
 * 极端运维场景（锁行被外部删除）该锁名将不可抢直至重启——以可观测性换 50% 的锁 SQL 削减。
 *
 * @author muyi
 * @date 2026/10/27
 */
public final class LockTableProvisioner {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(LockTableProvisioner.class);

    /** MyBatis Mapper（建表与播种语句载体，SQL 全在 Mapper XML） */
    private final ScheduleLockMapper mapper;

    /** 锁表名（已过白名单校验） */
    private final String tableName;

    /** 种子行主键 id 生成器（starsite-sequence 组件——组件表物理主键标准来源） */
    private final java.util.function.LongSupplier idGenerator;

    /** 已播种的锁名（实例内记忆）：种子行每锁名只需插一次 */
    private final Set<String> seeded = ConcurrentHashMap.newKeySet();

    /**
     * 构造就绪器。
     *
     * @param mapper      MyBatis Mapper
     * @param tableName   锁表名（与 DbLockClient 使用同一表名）
     * @param idGenerator 种子行主键 id 生成器（sequence 组件的 next()）
     * @throws IllegalArgumentException 表名为 null 或含白名单外字符
     */
    public LockTableProvisioner(ScheduleLockMapper mapper, String tableName,
                                java.util.function.LongSupplier idGenerator) {
        if (tableName == null || !tableName.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("tableName 仅允许字母数字下划线: " + tableName);
        }
        this.mapper = mapper;
        this.tableName = tableName;
        this.idGenerator = idGenerator;
    }

    /**
     * 幂等建表（IF NOT EXISTS）。失败只记 warn 不抛出：表可能由 DBA/迁移脚本统一管理，
     * 真有问题会在首次争锁时暴露——初始化不应阻断应用启动。
     */
    public void ensureTable() {
        try {
            mapper.createTable(tableName);
            log.info("[starsite-lock] 锁表 {} 就绪（IF NOT EXISTS）", tableName);
        } catch (RuntimeException e) {
            log.warn("[starsite-lock] 建表检查失败（不阻断启动）: {}", e.getMessage());
        }
    }

    /**
     * 播种锁行（每锁名一次）：行不存在则插入（id 由 sequence 组件生成）。
     * 已播种过直接返回；播种 SQL 异常吞掉——调用方（争锁路径）紧随的 UPDATE
     * 会以同样方式失败并走各自的容错语义。
     *
     * @param lockName 锁名
     */
    public void seedIfNeeded(String lockName) {
        if (seeded.contains(lockName)) {
            return;
        }
        try {
            mapper.seedLockRow(tableName, idGenerator.getAsLong(), lockName);
            seeded.add(lockName);
        } catch (RuntimeException e) {
            log.debug("[starsite-lock] 种子行已存在或播种失败（继续争锁）: lock={}, err={}",
                    lockName, e.getMessage());
        }
    }
}
