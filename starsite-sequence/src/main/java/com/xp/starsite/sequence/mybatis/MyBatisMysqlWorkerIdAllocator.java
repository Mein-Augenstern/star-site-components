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
package com.xp.starsite.sequence.mybatis;

import com.xp.starsite.sequence.allocator.WorkerIdAllocator;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.mybatis.spring.SqlSessionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MyBatis 版 workerId 分配器：租约语义与 raw JDBC 版完全等价（__global__ 池 + LAST_INSERT_ID 原子取号），
 * SQL 落在与 {@link SequenceMapper} 同包的 {@code SequenceMapper.xml}。
 * <p>
 * 事务保证：LAST_INSERT_ID 是 MySQL 连接级会话变量——takeFromGlobalPool + selectLastInsertId
 * 必须在同一连接内执行。编程式事务（TransactionTemplate.execute）把两步锁在同一个连接，
 * 执行期间连接不归还池。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class MyBatisMysqlWorkerIdAllocator implements WorkerIdAllocator {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(MyBatisMysqlWorkerIdAllocator.class);

    /**
     * 表结构契约列（BUG-009 结构校验）：表存在但缺任一列 = 陈旧结构，
     * 取号期 fail-fast（与 JDBC 版 MysqlWorkerIdAllocator 保持同一契约集）。
     */
    private static final java.util.Set<String> REQUIRED_COLUMNS = java.util.Set.of(
            "ID", "SERVICE_NAME", "WORKER_ID", "INSTANCE", "GMT_CREATE", "GMT_MODIFIED");

    /** 结构校验只做一次（幂等标志） */
    private volatile boolean schemaVerified = false;

    /** 全局池 key */
    public static final String GLOBAL_POOL_KEY = "__global__";

    /** 表名白名单 */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    /** MyBatis Mapper */
    private final SequenceMapper mapper;

    /** MyBatis 会话模板（获取受事务保护的连接） */
    private final SqlSessionTemplate sessionTemplate;

    /** 编程式事务模板（保证两步 SQL 同连接） */
    private final TransactionTemplate txTemplate;

    /** sequence 表名 */
    private final String tableName;

    /** 服务名 */
    private final String serviceName;

    /** 实例标记 */
    private final String instanceTag;

    /** 机器位上限 */
    private final long maxWorkerId;

    /** 审计行主键 id 生成器（starsite-sequence 组件——组件表物理主键标准来源） */
    private final LongSupplier idGenerator;

    /** 回绕策略名（RESET / FAIL） */
    private final String wraparoundPolicy;

    /**
     * 全参构造。
     *
     * @param mapper            MyBatis Mapper
     * @param sessionTemplate   会话模板
     * @param txTemplate        事务模板（LAST_INSERT_ID 两步同连接）
     * @param tableName         sequence 表名
     * @param serviceName       服务名
     * @param instanceTag       实例标记
     * @param workerIdBits      机器位宽
     * @param wraparoundPolicy  回绕策略名
     * @param idGenerator       审计行主键 id 生成器（sequence 组件的 next()）
     */
    public MyBatisMysqlWorkerIdAllocator(SequenceMapper mapper, SqlSessionTemplate sessionTemplate,
                                         TransactionTemplate txTemplate, String tableName,
                                         String serviceName, String instanceTag,
                                         int workerIdBits, String wraparoundPolicy,
                                         LongSupplier idGenerator) {
        if (tableName == null || !SAFE_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("tableName 仅允许字母数字下划线: " + tableName);
        }
        this.mapper = mapper;
        this.sessionTemplate = sessionTemplate;
        this.txTemplate = txTemplate;
        this.tableName = tableName;
        this.serviceName = serviceName;
        this.instanceTag = instanceTag;
        this.maxWorkerId = ~(-1L << workerIdBits);
        this.wraparoundPolicy = wraparoundPolicy;
        this.idGenerator = idGenerator;
    }

    /**
     * 从 sequence 表租约一个 workerId（事务内两步：UPDATE 取号 → SELECT 读回）。
     *
     * @return 全局唯一机器位
     * @throws RuntimeException 租约失败或超上限 FAIL 策略
     */
    @Override
    public long allocate() {
        mapper.createTable(tableName);
        verifyTableColumns();
        mapper.seedGlobalPool(tableName, GLOBAL_POOL_KEY, "pool");

        Long workerId = txTemplate.execute(status -> {
            mapper.takeFromGlobalPool(tableName, instanceTag, GLOBAL_POOL_KEY);
            return mapper.selectLastInsertId();
        });
        if (workerId == null) {
            throw new IllegalStateException("事务内取号失败（返回 null）");
        }
        mapper.recordOwnership(tableName, idGenerator.getAsLong(), serviceName, workerId, instanceTag);

        if (workerId > maxWorkerId) {
            if ("FAIL".equals(wraparoundPolicy)) {
                throw new IllegalStateException("全局池已分配到 " + workerId
                        + " 超过上限 " + maxWorkerId + "，按 FAIL 策略拒绝");
            }
            log.warn("[starsite-sequence] workerId 回绕：全局池 {} 超上限 {}，重置重取",
                    workerId, maxWorkerId);
            mapper.resetGlobalPool(tableName, instanceTag + "(reset)", GLOBAL_POOL_KEY);
            Long reTaken = txTemplate.execute(status -> {
                mapper.takeFromGlobalPool(tableName, instanceTag, GLOBAL_POOL_KEY);
                return mapper.selectLastInsertId();
            });
            if (reTaken == null) {
                // 与 JDBC 版语义对齐：事务内取号失败抛异常（静默默认 1 会与 standalone 默认撞号）
                throw new IllegalStateException("回绕重取号失败（事务返回 null）——拒绝静默默认 workerId");
            }
            workerId = reTaken;
            mapper.recordOwnership(tableName, idGenerator.getAsLong(), serviceName, workerId, instanceTag);
        }
        return workerId;
    }

    /**
     * 描述。
     *
     * @return 形如 "mybatis-lease(table=sys_sequence, service=svc)" 的描述
     */
    @Override
    public String describe() {
        return "mybatis-lease(table=%s, service=%s, tag=%s)".formatted(tableName, serviceName, instanceTag);
    }

    /**
     * 结构校验（BUG-009，与 JDBC 版 MysqlWorkerIdAllocator 同语义）：
     * 表<b>存在</b>但缺契约列 → 抛错阻断（运行期必然以更糟的方式失败——缺列时
     * 静默回落 hash workerId，跨服务撞号）；表不存在或校验查询失败 → 只告警不阻断
     * （表交由 DBA/迁移脚本管理）。表名经白名单校验，元数据查询全程 #{} 参数绑定。
     */
    private void verifyTableColumns() {
        if (schemaVerified) {
            return;
        }
        java.util.List<String> columns;
        try {
            columns = mapper.selectColumns(tableName);
        } catch (RuntimeException e) {
            log.warn("[starsite-sequence] 结构校验查询失败（跳过校验，不阻断取号）: {}", e.getMessage());
            return;
        }
        if (columns == null || columns.isEmpty()) {
            // 空集不置位已验标志：createTable 随后创建的表下一轮分配自然补验
            log.warn("[starsite-sequence] 表 {} 暂不存在或不可见（建表随后执行或由迁移脚本管理），本轮跳过结构校验", tableName);
            return;
        }
        schemaVerified = true;
        java.util.Set<String> actual = columns.stream()
                .map(c -> c.toUpperCase())
                .collect(java.util.stream.Collectors.toSet());
        var missing = REQUIRED_COLUMNS.stream().filter(c -> !actual.contains(c)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(("表 %s 已存在但缺少列 %s——结构陈旧（旧版 DDL 初始化或组件升级遗留）。"
                    + "请按 resources/sql/mysql/sys_sequence_ddl.sql 迁移（ALTER 补列或 DROP 重建），组件不自动 ALTER")
                    .formatted(tableName, missing));
        }
        log.debug("[starsite-sequence] 表 {} 结构校验通过（{} 列契约）", tableName, REQUIRED_COLUMNS.size());
    }
}
