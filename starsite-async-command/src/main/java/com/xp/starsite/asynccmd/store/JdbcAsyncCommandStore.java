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

import com.xp.starsite.asynccmd.model.AsyncCommand;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * MySQL 表存储（单机/分布式通用形态）：两步令牌法认领，多实例并发互斥。
 * <p>
 * 全部 SQL 语句外提至 {@link AsyncCommandSql}（方言抽象——语句集中可见、
 * 可替换适配其他数据库/ORM；默认 MySQL 实现）。本类只做语句渲染
 * （{@code {table}} 占位替换，表名经白名单校验防注入）、参数绑定与行映射。
 * 事务写法：跨语句原子操作（如未来"认领 + 落业务表"）经
 * {@code TransactionTemplate.execute(...)} 回调式编程事务，不用 @Transactional 注解——
 * 组件作为库不感染宿主的事务代理配置。
 * <p>
 * 与 star-site-backend star-async 的 async_command 表结构兼容（平滑迁移）；
 * init-ddl=true 时经方言语句自动建表（增量安全）。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class JdbcAsyncCommandStore implements AsyncCommandStore {

    /** 日志（slf4j） */
    private static final Logger log = LoggerFactory.getLogger(JdbcAsyncCommandStore.class);

    /** 表名白名单（仅字母数字下划线）：表名须拼接进 SQL 无法参数化，白名单校验是防注入的唯一手段 */
    private static final java.util.regex.Pattern SAFE_IDENTIFIER =
            java.util.regex.Pattern.compile("[A-Za-z0-9_]+");

    /** JDBC 模板（无状态，线程安全；事务由调用方经 TransactionTemplate 编排） */
    private final JdbcTemplate jdbc;

    /** 命令表名（已过白名单校验） */
    private final String table;

    /** SQL 方言（语句全部来自这里，本类零硬编码 SQL） */
    private final AsyncCommandSql sql;

    /** 时间源（悬挂回收阈值计算；测试注入假时钟用——与 claim 的调用方时钟同源） */
    private final com.xp.starsite.asynccmd.clock.TimeSource time;

    /** 主键 id 生成器（starsite-sequence 组件的 next()——组件表物理主键标准来源） */
    private final LongSupplier idGenerator;

    /** 行映射器：下划线列名 → 驼峰字段（静态复用；详情/执行路径全列含 payload） */
    private static final RowMapper<AsyncCommand> MAPPER = (rs, i) -> {
        AsyncCommand c = new AsyncCommand();
        c.setId(rs.getLong("id"));
        c.setCmdType(rs.getString("cmd_type"));
        c.setBizKey(rs.getString("biz_key"));
        c.setPayload(rs.getString("payload"));
        c.setStatus(rs.getString("status"));
        c.setMaxRetry(rs.getInt("max_retry"));
        c.setRetryCount(rs.getInt("retry_count"));
        c.setNextRunAt(rs.getTimestamp("next_run_at") == null
                ? null : rs.getTimestamp("next_run_at").toLocalDateTime());
        c.setLastError(rs.getString("last_error"));
        c.setOwner(rs.getString("owner"));
        c.setClaimToken(rs.getString("claim_token"));
        c.setGmtCreate(rs.getTimestamp("gmt_create") == null
                ? null : rs.getTimestamp("gmt_create").toLocalDateTime());
        c.setGmtModified(rs.getTimestamp("gmt_modified") == null
                ? null : rs.getTimestamp("gmt_modified").toLocalDateTime());
        return c;
    };

    /**
     * 列表行映射器（selectRecent/selectPage 专用）：不读 payload 列——列表语句显式列清单
     * 不含该大列（单行最大 64KB），用全列映射器会因列缺失抛 SQLException。
     * payload 在列表结果中为 null，需要载荷走 findById/findByToken。
     */
    private static final RowMapper<AsyncCommand> LIST_MAPPER = (rs, i) -> {
        AsyncCommand c = new AsyncCommand();
        c.setId(rs.getLong("id"));
        c.setCmdType(rs.getString("cmd_type"));
        c.setBizKey(rs.getString("biz_key"));
        c.setStatus(rs.getString("status"));
        c.setMaxRetry(rs.getInt("max_retry"));
        c.setRetryCount(rs.getInt("retry_count"));
        c.setNextRunAt(rs.getTimestamp("next_run_at") == null
                ? null : rs.getTimestamp("next_run_at").toLocalDateTime());
        c.setLastError(rs.getString("last_error"));
        c.setOwner(rs.getString("owner"));
        c.setClaimToken(rs.getString("claim_token"));
        c.setGmtCreate(rs.getTimestamp("gmt_create") == null
                ? null : rs.getTimestamp("gmt_create").toLocalDateTime());
        c.setGmtModified(rs.getTimestamp("gmt_modified") == null
                ? null : rs.getTimestamp("gmt_modified").toLocalDateTime());
        return c;
    };

    /**
     * 便捷构造（MySQL 默认方言 + 自动建表）。
     *
     * @param jdbc      JDBC 模板
     * @param tableName 命令表名（仅字母数字下划线）
     */
    public JdbcAsyncCommandStore(JdbcTemplate jdbc, String tableName, LongSupplier idGenerator) {
        this(jdbc, tableName, new AsyncCommandSql.MySql(), true, idGenerator);
    }

    /**
     * 全参构造。
     *
     * @param jdbc      JDBC 模板
     * @param tableName 命令表名（仅字母数字下划线）
     * @param sql       SQL 方言（语句来源；自定义方言实现其他数据库/ORM 适配）
     * @param initDdl   true 时构造期自动建表（IF NOT EXISTS 增量安全）；false 交由迁移脚本管理
     * @param idGenerator 主键 id 生成器（sequence 组件的 next()；insert 前为命令分配物理主键）
     * @throws IllegalArgumentException 表名为 null 或含白名单外字符
     */
    public JdbcAsyncCommandStore(JdbcTemplate jdbc, String tableName, AsyncCommandSql sql, boolean initDdl,
                                 LongSupplier idGenerator) {
        this(jdbc, tableName, sql, initDdl, idGenerator,
                com.xp.starsite.asynccmd.clock.TimeSource.system());
    }

    /**
     * 全参构造（显式时间源——测试注入假时钟构造悬挂场景）。
     *
     * @param jdbc      JDBC 模板
     * @param tableName 命令表名（仅字母数字下划线）
     * @param sql       SQL 方言（语句来源；自定义方言实现其他数据库/ORM 适配）
     * @param initDdl   true 时构造期自动建表（IF NOT EXISTS 增量安全）；false 交由迁移脚本管理
     * @param idGenerator 主键 id 生成器（sequence 组件的 next()；insert 前为命令分配物理主键）
     * @param time      时间源（reclaimStale 阈值计算；与 claim 的调用方时钟同源）
     * @throws IllegalArgumentException 表名为 null 或含白名单外字符
     */
    public JdbcAsyncCommandStore(JdbcTemplate jdbc, String tableName, AsyncCommandSql sql, boolean initDdl,
                                 LongSupplier idGenerator,
                                 com.xp.starsite.asynccmd.clock.TimeSource time) {
        if (tableName == null || !SAFE_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("tableName 仅允许字母数字下划线: " + tableName);
        }
        this.jdbc = jdbc;
        this.table = tableName;
        this.sql = sql;
        this.idGenerator = idGenerator;
        this.time = time;
        if (initDdl) {
            ensureTable();
        }
    }

    /**
     * 渲染语句：方言语句的 {table} 占位替换为白名单表名。
     *
     * @param statement 方言原始语句
     * @return 可执行语句
     */
    private String render(String statement) {
        return statement.replace("{table}", table);
    }

    /**
     * 自动建表（幂等 IF NOT EXISTS）。建表失败只记 warn 不抛出：
     * 表可能由 DBA/迁移脚本统一管理，真有问题会在首条命令提交时暴露。
     */
    private void ensureTable() {
        try {
            jdbc.execute(render(sql.createTable()));
            log.info("[starsite-async-command] 表 {} 就绪（IF NOT EXISTS）", table);
        } catch (Exception e) {
            // 建表失败不阻断启动（表可能由 DBA/迁移脚本管理），首条命令提交时即会暴露
            log.warn("[starsite-async-command] 建表检查失败（不阻断启动）: {}", e.getMessage());
        }
    }

    /** {@inheritDoc} 自增 id 经 GeneratedKeyHolder 回填 */
    @Override
    public void insert(AsyncCommand cmd) {
        // 组件表物理主键标准来源：starsite-sequence 组件（去 AUTO_INCREMENT）
        if (cmd.getId() == null) {
            cmd.setId(idGenerator.getAsLong());
        }
        String stmt = render(sql.insert());
        jdbc.update(stmt, ps -> {
            ps.setLong(1, cmd.getId());
            ps.setString(2, cmd.getCmdType());
            ps.setString(3, cmd.getBizKey() == null ? "" : cmd.getBizKey());
            ps.setString(4, cmd.getPayload());
            ps.setString(5, cmd.getStatus());
            ps.setInt(6, cmd.getMaxRetry());
            ps.setInt(7, cmd.getRetryCount());
            ps.setObject(8, cmd.getNextRunAt());
            ps.setString(9, cmd.getOwner());
        });
    }

    /** {@inheritDoc} */
    @Override
    public AsyncCommand findById(long id) {
        List<AsyncCommand> out = jdbc.query(render(sql.findById()), MAPPER, id);
        return out.isEmpty() ? null : out.get(0);
    }

    /** {@inheritDoc} */
    @Override
    public long countUnfinished(String cmdType, String bizKey) {
        Long n = jdbc.queryForObject(render(sql.countUnfinished()), Long.class, cmdType, bizKey);
        return n == null ? 0 : n;
    }

    /** {@inheritDoc} 到期判定用参数时间（与调用方/测试时钟一致），gmt_modified 仍取库时间 */
    @Override
    public int claim(String token, int limit, LocalDateTime now) {
        return jdbc.update(render(sql.claim()), token, now, now, limit);
    }

    /** {@inheritDoc} */
    @Override
    public List<AsyncCommand> findByToken(String token) {
        return jdbc.query(render(sql.findByToken()), MAPPER, token);
    }

    /** {@inheritDoc} 到期判定用库时钟 NOW()：IMMEDIATE 提交线程与库视图一致 */
    @Override
    public boolean claimById(long id, String token) {
        return jdbc.update(render(sql.claimById()), token, id) > 0;
    }

    /** {@inheritDoc} 悬挂判定与回写都用库时钟（claim 判业务到期，这里判"DB 眼里多久没动静"） */
    @Override
    public int reclaimStale(int staleAfterSeconds, int limit) {
        // 阈值时刻由注入时钟计算后参数绑定（消 INTERVAL 方言差异；与 claim 同源可测）
        return jdbc.update(render(sql.reclaimStale()),
                time.now().minusSeconds(staleAfterSeconds), limit);
    }

    /** {@inheritDoc} */
    @Override
    public int updateState(AsyncCommand cmd) {
        return jdbc.update(render(sql.updateState()),
                cmd.getStatus(), cmd.getClaimToken(), cmd.getRetryCount(),
                cmd.getNextRunAt(), cmd.getLastError(), cmd.getId());
    }

    /** {@inheritDoc} */
    @Override
    public long countByStatus(String status) {
        Long n = jdbc.queryForObject(render(sql.countByStatus()), Long.class, status);
        return n == null ? 0 : n;
    }

    /** {@inheritDoc} */
    @Override
    public long countBacklogDue(LocalDateTime now) {
        Long n = jdbc.queryForObject(render(sql.countBacklogDue()), Long.class, now);
        return n == null ? 0 : n;
    }

    /** {@inheritDoc} */
    @Override
    public List<AsyncCommand> selectRecent(int limit) {
        return jdbc.query(render(sql.selectRecent()), LIST_MAPPER, limit);
    }

    /** {@inheritDoc} 可选过滤经白名单拼 WHERE 片段（status/cmdType 为值参数绑定，非拼接） */
    @Override
    public long countPage(String status, String cmdType) {
        StringBuilder stmt = new StringBuilder(render(sql.countPage()));
        Object[] args = appendFilters(stmt, status, cmdType);
        Long n = jdbc.queryForObject(stmt.toString(), Long.class, args);
        return n == null ? 0 : n;
    }

    /** {@inheritDoc} */
    @Override
    public List<AsyncCommand> selectPage(String status, String cmdType, long offset, int size) {
        StringBuilder stmt = new StringBuilder(render(sql.selectPage()));
        Object[] filtered = appendFilters(stmt, status, cmdType);
        stmt.append(' ').append(sql.pageOrderLimit());
        Object[] args = new Object[filtered.length + 2];
        System.arraycopy(filtered, 0, args, 0, filtered.length);
        args[filtered.length] = size;
        args[filtered.length + 1] = offset;
        return jdbc.query(stmt.toString(), LIST_MAPPER, args);
    }

    /**
     * 向语句追加可选过滤片段：status/cmdType 非空时各追加一段
     * {@code AND col = ?}，值进参数数组（参数绑定，无值拼接）。
     *
     * @param stmt   语句构建器（就地追加）
     * @param status 状态过滤（空/blank 跳过）
     * @param cmdType 类型过滤（空/blank 跳过）
     * @return 本次实际绑定的参数值数组
     */
    private Object[] appendFilters(StringBuilder stmt, String status, String cmdType) {
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            stmt.append(' ').append(sql.filterStatus());
            args.add(status);
        }
        if (cmdType != null && !cmdType.isBlank()) {
            stmt.append(' ').append(sql.filterCmdType());
            args.add(cmdType);
        }
        return args.toArray();
    }
}
