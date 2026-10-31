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
package com.xp.starsite.sequence.allocator;

import com.xp.starsite.sequence.generator.SnowflakeIdGenerator;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 分布式形态机器位分配器（MySQL sequence 表原子租约）。
 * <p>
 * 语义（对齐《分布式 ID：从雪花算法到生产实现》的 __global__ 池设计）：
 * <ul>
 *   <li><b>分配必须单点</b>：workerId 从全局池计数行（service_name = '__global__'）经
 *       {@code UPDATE ... SET worker_id = LAST_INSERT_ID(worker_id + 1)} 原子自增取出，
 *       全平台绝不重复——各服务独立从 0 数会立刻跨服务撞号，故不做每服务独立计数。</li>
 *   <li><b>审计分服务</b>：每个服务实例的占用记录（谁在何时占用过哪个机器位）另落各自的
 *       sequence 行，不影响分配语义。</li>
 *   <li><b>回绕策略</b>：分配值超过机器位上限时——{@link WraparoundPolicy#RESET} 重置全局池
 *       为 0 后重新取号并告警（回绕窗口内与存活旧实例同 id 需要「同毫秒同序列」同时命中，
 *       概率可忽略但必须可见）；{@link WraparoundPolicy#FAIL} 直接拒绝（保守场景）。</li>
 *   <li><b>建表随组件走</b>：表名可配（默认 sys_sequence），init-ddl=true 时自动
 *       CREATE TABLE IF NOT EXISTS（增量安全），也可交由 DBA 迁移脚本管理。</li>
 * </ul>
 * 全部 SQL 参数绑定；表名校验防注入（仅允许字母数字下划线）。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class MysqlWorkerIdAllocator implements WorkerIdAllocator {

    /** 全局池计数行 key（workerId 从这一行原子自增取出，全平台唯一） */
    public static final String GLOBAL_POOL_KEY = "__global__";

    /** 组件日志器（回绕告警用） */
    private static final Logger log = LoggerFactory.getLogger(MysqlWorkerIdAllocator.class);

    /** 合法表名白名单（仅字母数字下划线）：表名以拼接方式进 SQL，靠该校验防注入 */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    /**
     * 回绕策略：全局池分配值超过机器位上限（2^workerIdBits - 1）时的处置方式。
     */
    public enum WraparoundPolicy {
        /** 重置全局池为 0 重新取号并告警（默认；回绕窗口理论撞号概率可忽略但告警保证可见） */
        RESET,
        /** 超上限直接抛异常拒绝启动（保守场景，宁可起不来也不冒撞号风险） */
        FAIL
    }

    /** 目标库连接池，allocate 时借用连接执行租约 SQL */
    private final DataSource dataSource;

    /** sequence 表表名（构造时已通过白名单校验） */
    private final String tableName;

    /** 本服务名：审计行的 service_name 主键，区分"哪个服务占了哪个号" */
    private final String serviceName;

    /** 本实例标识：写入占用记录，用于审计定位到具体进程 */
    private final String instanceTag;

    /** 是否在分配前自动执行幂等建表 DDL（false = 表交由 DBA 迁移脚本管理） */
    private final boolean initDdl;

    /** 机器位上限 = 2^workerIdBits - 1，分配值超过即触发回绕策略 */
    private final long maxWorkerId;

    /** 回绕策略：RESET 重置全局池重取 / FAIL 抛异常拒绝 */
    private final WraparoundPolicy wraparoundPolicy;

    /** SQL 方言（全部租约语句来源，本类零硬编码 SQL） */
    private final SequenceSql sql;

    /**
     * 表结构契约列（BUG-009 结构校验）：表存在但缺任一列 = 旧版初始化/组件升级后的
     * 陈旧结构，运行期必然以更糟的方式失败（如缺 id 列时静默回落 hash workerId 撞号），
     * 故启动期 fail-fast。表不存在则视为 DBA 管理，跳过校验。
     */
    private static final java.util.Set<String> REQUIRED_COLUMNS = java.util.Set.of(
            "ID", "SERVICE_NAME", "WORKER_ID", "INSTANCE", "GMT_CREATE", "GMT_MODIFIED");

    /** 结构校验只做一次（幂等标志；分配器生命周期内表结构不回退） */
    private volatile boolean schemaVerified = false;

    /** 审计行主键 id 生成器（缺省为服务名派生 workerId 的自举雪花——跨服务唯一，无需先有正式发号器） */
    private final LongSupplier idGenerator;

    /**
     * 构造 MySQL 租约分配器（审计行 id 用服务名派生 workerId 的自举雪花）。
     *
     * @param dataSource        目标库连接池，不可为 null
     * @param tableName         sequence 表表名，仅允许字母数字下划线（防 SQL 注入）
     * @param serviceName       服务名（审计行 key），各服务互不相同
     * @param instanceTag       实例标识（审计可见），建议含主机/pid 等可定位信息
     * @param initDdl           true 时分配前自动 CREATE TABLE IF NOT EXISTS 并播种全局池行
     * @param workerIdBits      机器位宽度（位），决定本分配器可分配的 workerId 上限
     * @param wraparoundPolicy  分配值超上限时的回绕策略
     * @throws IllegalArgumentException dataSource 为 null，或 tableName 为 null / 含白名单外字符
     */
    public MysqlWorkerIdAllocator(DataSource dataSource, String tableName, String serviceName,
                                  String instanceTag, boolean initDdl, int workerIdBits,
                                  WraparoundPolicy wraparoundPolicy) {
        this(dataSource, tableName, serviceName, instanceTag, initDdl, workerIdBits,
                wraparoundPolicy, new SequenceSql.MySql());
    }

    /**
     * 全参构造（自定义方言；审计行 id 用自举雪花）。
     *
     * @param dataSource        目标库连接池，不可为 null
     * @param tableName         sequence 表表名，仅允许字母数字下划线（防 SQL 注入）
     * @param serviceName       服务名（审计行 key），各服务互不相同
     * @param instanceTag       实例标识（审计可见），建议含主机/pid 等可定位信息
     * @param initDdl           true 时分配前自动 CREATE TABLE IF NOT EXISTS 并播种全局池行
     * @param workerIdBits      机器位宽度（位），决定本分配器可分配的 workerId 上限
     * @param wraparoundPolicy  分配值超上限时的回绕策略
     * @param sql               SQL 方言（语句来源；自定义实现适配其他数据库/ORM）
     * @throws IllegalArgumentException dataSource 为 null，或 tableName 为 null / 含白名单外字符
     */
    public MysqlWorkerIdAllocator(DataSource dataSource, String tableName, String serviceName,
                                  String instanceTag, boolean initDdl, int workerIdBits,
                                  WraparoundPolicy wraparoundPolicy, SequenceSql sql) {
        this(dataSource, tableName, serviceName, instanceTag, initDdl, workerIdBits,
                wraparoundPolicy, sql, bootstrapIdGenerator(serviceName));
    }

    /**
     * 全参构造（显式审计行 id 生成器）。
     *
     * @param dataSource        目标库连接池，不可为 null
     * @param tableName         sequence 表表名，仅允许字母数字下划线（防 SQL 注入）
     * @param serviceName       服务名（审计行 key），各服务互不相同
     * @param instanceTag       实例标识（审计可见），建议含主机/pid 等可定位信息
     * @param initDdl           true 时分配前自动 CREATE TABLE IF NOT EXISTS 并播种全局池行
     * @param workerIdBits      机器位宽度（位），决定本分配器可分配的 workerId 上限
     * @param wraparoundPolicy  分配值超上限时的回绕策略
     * @param sql               SQL 方言（语句来源；自定义实现适配其他数据库/ORM）
     * @param idGenerator       审计行主键 id 生成器（分配器自举阶段即可用——如服务名派生 workerId 的雪花）
     * @throws IllegalArgumentException dataSource 为 null，或 tableName 为 null / 含白名单外字符
     */
    public MysqlWorkerIdAllocator(DataSource dataSource, String tableName, String serviceName,
                                  String instanceTag, boolean initDdl, int workerIdBits,
                                  WraparoundPolicy wraparoundPolicy, SequenceSql sql,
                                  LongSupplier idGenerator) {
        if (dataSource == null) {
            throw new IllegalArgumentException("mysql 模式需要 DataSource（检查数据源配置）");
        }
        if (tableName == null || !SAFE_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("tableName 仅允许字母数字下划线: " + tableName);
        }
        this.dataSource = dataSource;
        this.tableName = tableName;
        this.serviceName = serviceName;
        this.instanceTag = instanceTag;
        this.initDdl = initDdl;
        this.maxWorkerId = ~(-1L << workerIdBits);
        this.wraparoundPolicy = wraparoundPolicy;
        this.sql = sql;
        this.idGenerator = idGenerator;
    }

    /**
     * 自举 id 生成器：服务名 hash 派生 workerId 的独立雪花（CLAMP 钳制回拨）。
     * 分配器建正式发号器之前就要写审计行，此时唯一可用的确定性离散源就是服务名——
     * 跨服务不同号即满足审计行主键唯一性；固定值（如恒为 1）会让多服务审计行在
     * 主键上互撞并经 ON DUPLICATE KEY 交叉覆盖，不可用。
     *
     * @param serviceName 服务名（派生 workerId 的种子）
     * @return 自举发号 supplier
     */
    private static LongSupplier bootstrapIdGenerator(String serviceName) {
        long bootstrapWorkerId = Math.floorMod(serviceName.hashCode(), 1024);
        SnowflakeIdGenerator bootstrap =
                new SnowflakeIdGenerator(
                        bootstrapWorkerId, 1767225600000L, 10, 12, 2000,
                        SnowflakeIdGenerator.BackwardPolicy.CLAMP);
        return bootstrap::nextId;
    }

    /**
     * 从 MySQL sequence 表的全局池原子租约一个 workerId，并落本实例的审计行。
     * <p>
     * 流程：可选幂等建表 → 全局池行原子自增取号 → 记录服务占用 →
     * 取号值超过机器位上限时按回绕策略重置重取（RESET）或抛错拒绝（FAIL）。
     *
     * @return 全局唯一的机器位（正常为全局池顺序递增值；RESET 回绕后为重置后重新取出的值）
     * @throws SQLException 数据库不可达或 SQL 执行失败；或取号超上限且策略为 {@link WraparoundPolicy#FAIL} 时以 SQLException 拒绝
     */
    @Override
    public long allocate() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            if (initDdl) {
                ensureTable(conn);
            }
            long workerId = takeFromGlobalPool(conn);
            recordOwnership(conn, workerId);
            if (workerId > maxWorkerId) {
                if (wraparoundPolicy == WraparoundPolicy.FAIL) {
                    throw new SQLException("全局池已分配到 %d 超过机器位上限 %d，按 FAIL 策略拒绝"
                            .formatted(workerId, maxWorkerId));
                }
                // RESET：重置全局池后重新取号（两次取号间仍有理论撞号窗口，靠告警可见）
                log.warn("[starsite-sequence] workerId 回绕：全局池已分配到 {} 超过机器位上限 {}，"
                        + "重置全局池重新取号（回绕窗口内与存活旧实例同 id 需同毫秒同序列同时命中，"
                        + "概率可忽略但必须可见；实例规模接近上限时应扩位宽）", workerId, maxWorkerId);
                resetGlobalPool(conn);
                workerId = takeFromGlobalPool(conn);
                recordOwnership(conn, workerId);
            }
            return workerId;
        }
    }

    /**
     * 分配器形态描述。
     *
     * @return 形如 "mysql-lease(table=sys_sequence, service=svc, tag=tag)" 的描述，用于启动日志
     */
    @Override
    public String describe() {
        return "mysql-lease(table=%s, service=%s, tag=%s)".formatted(tableName, serviceName, instanceTag);
    }

    /**
     * 渲染方言语句：{table} 占位替换为白名单表名。
     *
     * @param statement 方言原始语句
     * @return 可执行语句
     */
    private String render(String statement) {
        return statement.replace("{table}", tableName);
    }

    /**
     * 幂等建表并播种全局池种子行：CREATE TABLE IF NOT EXISTS 后
     * INSERT IGNORE 一条 service_name = __global__、worker_id = 0 的计数行（已存在则忽略）。
     *
     * @param conn 目标库连接
     * @throws SQLException DDL 或种子行写入失败
     */
    private void ensureTable(Connection conn) throws SQLException {
        // 校验先于一切：漂移表上 createTable 是 no-op、随后的 seed INSERT 会缺列裸报
        // Unknown column——校验先行才能给出带迁移指引的 fail-fast（BUG-009）
        verifyTableColumns(conn);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(render(sql.createTable()));
            try (PreparedStatement seed = conn.prepareStatement(render(sql.seedGlobalPool()))) {
                seed.setString(1, GLOBAL_POOL_KEY);
                seed.setString(2, "pool");
                seed.executeUpdate();
            }
        }
    }

    /**
     * 结构校验（BUG-009）：表<b>存在</b>但缺契约列 → 抛错阻断（fail-fast）；
     * 表不存在 → 只告警不阻断（表交由 DBA/迁移脚本管理，建表 DDL 自会创建全列）。
     * 校验失败与建表失败的处置差异是刻意的：前者运行期必然以更糟的方式失败
     * （缺列时报 Unknown column 并可能静默回落 hash workerId——跨服务撞号风险），
     * 后者可能只是权限或连接抖动。
     * <p>
     * 实现走 {@link DatabaseMetaData#getColumns}（JDBC 标准元数据 API）：
     * 零 SQL 语句、天然参数化，MySQL/H2 皆支持；表名匹配先按原名再按大写
     * （H2 未加引号标识符存大写，MySQL 依 lower_case_table_names）。
     *
     * @param conn 目标库连接
     * @throws IllegalStateException 表存在但结构陈旧（缺契约列），消息含迁移指引
     */
    private void verifyTableColumns(Connection conn) throws SQLException {
        if (schemaVerified) {
            return;
        }
        java.util.Set<String> actual = new java.util.HashSet<>();
        collectColumnNames(conn.getMetaData(), tableName, actual);
        if (actual.isEmpty()) {
            collectColumnNames(conn.getMetaData(), tableName.toUpperCase(), actual);
        }
        if (actual.isEmpty()) {
            // 空集不置位已验标志：init-ddl 建表后下一轮分配自然补验；
            // 真是 DBA 管理场景（建表权限缺失）也只每次分配多一条元数据查询
            log.warn("[starsite-sequence] 表 {} 暂不存在或不可见（init-ddl 将创建或由迁移脚本管理），本轮跳过结构校验", tableName);
            return;
        }
        schemaVerified = true;
        var missing = REQUIRED_COLUMNS.stream().filter(c -> !actual.contains(c)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(("表 %s 已存在但缺少列 %s——结构陈旧（旧版 DDL 初始化或组件升级遗留）。"
                    + "请按 resources/sql/mysql/sys_sequence_ddl.sql 迁移（ALTER 补列或 DROP 重建），组件不自动 ALTER")
                    .formatted(tableName, missing));
        }
        log.debug("[starsite-sequence] 表 {} 结构校验通过（{} 列契约）", tableName, REQUIRED_COLUMNS.size());
    }

    /**
     * 经 JDBC 元数据 API 收集表的列名（统一大写便于大小写无关比对）。
     *
     * @param meta  数据库元数据
     * @param table 表名（原样传入，由调用方负责大小写兜底重试）
     * @param out   收集目标（追加写入）
     * @throws SQLException 元数据访问失败
     */
    private static void collectColumnNames(DatabaseMetaData meta, String table,
                                           java.util.Set<String> out) throws SQLException {
        try (ResultSet rs = meta.getColumns(null, null, table, null)) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                if (name != null) {
                    out.add(name.toUpperCase());
                }
            }
        }
    }

    /**
     * 原子自增取出全局唯一机器位。
     * <p>
     * 原理：LAST_INSERT_ID(expr) 在 UPDATE 命中时把自增结果带回当前会话，
     * 随后同连接的 SELECT LAST_INSERT_ID() 读回——UPDATE 与 SELECT 必须同一连接；
     * 单条 UPDATE 原子完成"读改写"，多实例并发取号天然互不重复。
     *
     * @param conn 目标库连接（须保持到 SELECT LAST_INSERT_ID() 执行完，不能提前归还）
     * @return 本次取到的机器位（全局池上一值 + 1）
     * @throws SQLException UPDATE 执行失败，或 SELECT LAST_INSERT_ID() 无返回行（异常态）
     */
    private long takeFromGlobalPool(Connection conn) throws SQLException {
        try (PreparedStatement update = conn.prepareStatement(render(sql.takeFromGlobalPool()));
             PreparedStatement select = conn.prepareStatement(render(sql.selectLastInsertId()))) {
            update.setString(1, instanceTag);
            update.setString(2, GLOBAL_POOL_KEY);
            update.executeUpdate();
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("LAST_INSERT_ID() 无返回");
                }
                return rs.getLong(1);
            }
        }
    }

    /**
     * 记录服务实例的占用：upsert 各自服务的 sequence 行（不存在则插入，存在则覆盖更新）。
     * 仅作审计可见（谁在何时占用哪个号），不参与分配语义。
     *
     * @param conn     目标库连接
     * @param workerId 刚刚取到的机器位
     * @throws SQLException 审计行写入失败
     */
    private void recordOwnership(Connection conn, long workerId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(render(sql.recordOwnership()))) {
            stmt.setLong(1, idGenerator.getAsLong());
            stmt.setString(2, serviceName);
            stmt.setLong(3, workerId);
            stmt.setString(4, instanceTag);
            stmt.executeUpdate();
        }
    }

    /**
     * 将全局池计数行重置为 0（RESET 回绕策略用），instance 追加 "(wraparound-reset)" 标记留痕。
     *
     * @param conn 目标库连接
     * @throws SQLException 重置 UPDATE 执行失败
     */
    private void resetGlobalPool(Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(render(sql.resetGlobalPool()))) {
            stmt.setString(1, instanceTag + "(wraparound-reset)");
            stmt.setString(2, GLOBAL_POOL_KEY);
            stmt.executeUpdate();
        }
    }
}
