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
 * <b>分布式正确性论证（seeded 是性能提示，不是正确性依据）</b>：
 * <ul>
 *   <li>互斥判定的最终权威始终是 {@code DbLockClient} 的存储端原子 UPDATE——
 *       {@link #seeded} 只决定"要不要省一条播种 INSERT"，从不参与判定</li>
 *   <li>多 Pod 并发播种安全：INSERT ... WHERE NOT EXISTS 幂等（后到者 0 行非异常）；
 *       极窄竞态撞 uk 唯一键者吞异常且不记忆，下轮重试收敛</li>
 *   <li>记忆陈旧（外部删行/表重建）自愈：争锁未命中经 {@link #healIfRowLost}
 *       做存储端核实（findHolder），行丢失则作废记忆+重播种+重试——
 *       多 Pod 同时自愈时重播种幂等、重试仍走原子 UPDATE，恰一胜</li>
 *   <li>记忆陈旧但行已被他 Pod 重建并持有：findHolder 非空 → 不自愈不误抢，正常返回未获得</li>
 * </ul>
 * 任何 Pod 的 seeded 状态无论真假，tryLock 的返回值都与"没有缓存"时一致——
 * 差别只在 SQL 条数（记忆命中省 1 条，未命中路径 2 条）。
 * <p>
 * 性能：种子 SQL 每锁名每实例只执行一次，稳态下争锁只剩 1 条点查 UPDATE（省 50%）。
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

    /**
     * 已播种的锁名（<b>纯性能提示</b>，正确性不依赖——论证见类注释）：
     * 只用来省一条幂等 INSERT；陈旧时由 {@link #healIfRowLost} 在存储端核实后自愈。
     */
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
     * 播种锁行（每锁名一次，<b>性能提示而非正确性依据</b>）：行不存在则插入（id 由 sequence 生成）。
     * 已播种过直接返回；播种 SQL 异常吞掉——调用方（争锁路径）紧随的 UPDATE
     * 会以同样方式失败并走各自的容错语义。
     * <p>
     * 分布式语义：本记忆只是"省一条播种 SQL"的提示——正确性不依赖它。
     * 行被外部删除/表重建导致记忆陈旧时，由 {@link DbLockClient} 的未抢到路径
     * （findHolder 校验 + {@link #unseed} + 重播种重试）自愈，无需重启。
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

    /**
     * 行丢失自愈（存储供给职责——DbLockClient 的互斥语义不掺和恢复编排）：
     * 争锁未命中后调用，校验锁行存活性；行丢失（外部删行/表重建致播种记忆陈旧）
     * 则作废记忆并重播种，返回 true 提示调用方可重试争锁。
     * findHolder==null 的二义（行不存在/无人持有）由幂等播种天然收敛——
     * 无人持有时重播种为无操作，调用方重试即抢到。
     *
     * @param lockName 锁名
     * @return true 已自愈（重播种完成，值得重试争锁）；false 行健在（未抢到即他方持有）
     */
    public boolean healIfRowLost(String lockName) {
        if (findHolder(lockName) != null) {
            return false;
        }
        seeded.remove(lockName);
        seedIfNeeded(lockName);
        return true;
    }

    /**
     * 锁行是否存在于表中（自愈路径的存活性校验；SELECT locked_by 点查 uk(lock_name)）。
     * 注意返回 null 有二义（行不存在 / 行存在但无人持有）——调用方以幂等重播种 + 重试收敛，
     * 不需要区分。
     *
     * @param lockName 锁名
     * @return 当前持有者标识；行不存在或无人持有返回 null
     */
    public String findHolder(String lockName) {
        return mapper.findHolder(tableName, lockName);
    }
}
