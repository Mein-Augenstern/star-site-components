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

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 命令表 SQL 方言抽象：把全部 SQL 语句从存储实现中外提——语句集中可见、
 * 方言可替换（MySQL 默认实现；适配其他数据库/ORM 时实现本接口并经
 * starter 的 @ConditionalOnMissingBean 让位注入即可）。
 * <p>
 * 设计约定：
 * <ul>
 *   <li><b>SQL 配置外置铁律</b>：Java 代码零 SQL 字符串——MySQL 默认方言的语句在
 *       {@code resources/sql/mysql/async-command-mysql.properties}（key 与本接口方法
 *       一一对应），{@link MySql} 只做加载与缺键校验</li>
 *   <li>表名占位统一为 {@code {table}}（由存储层替换，表名经白名单校验防注入）</li>
 *   <li>参数一律 {@code ?} 占位符，值由调用方绑定——语句本身不含任何业务值；
 *       可选过滤与分页排序也经 {@link #filterStatus()}/{@link #filterCmdType()}/
 *       {@link #pageOrderLimit()} 提供片段，杜绝 Java 拼接 SQL 片段</li>
 * </ul>
 *
 * @author muyi
 * @date 2026/10/25
 */
public interface AsyncCommandSql {

    /** 建表语句（幂等 IF NOT EXISTS；{table} 为表名占位）。 */
    String createTable();

    /** 新建命令（参数：id, cmd_type, biz_key, payload, status, max_retry, retry_count, next_run_at, owner）。 */
    String insert();

    /** 按主键查询（全列含 payload——详情/执行路径）。 */
    String findById();

    /** 幂等检查：同 type+key 未完成（PENDING/RUNNING）计数。 */
    String countUnfinished();

    /** 批量令牌认领第 1 步（参数：token, now, now, limit；MySQL 无 UPDATE..RETURNING 的两步法）。 */
    String claim();

    /** 批量令牌认领第 2 步：按令牌取回（全列——执行路径）。 */
    String findByToken();

    /** 按 id 原子认领（IMMEDIATE 模式，参数：token, id；认领条数 &gt; 0 即成功）。 */
    String claimById();

    /** 崩溃回收：悬挂 RUNNING 回 PENDING（参数：threshold, limit；阈值时刻由存储层注入时钟计算）。 */
    String reclaimStale();

    /** 状态回写（status, claimToken, retryCount, nextRunAt, lastError, id）。 */
    String updateState();

    /** 按状态计数。 */
    String countByStatus();

    /** 到期积压计数（参数 now）。 */
    String countBacklogDue();

    /** 近 N 条（列表路径——不含 payload 大列）。 */
    String selectRecent();

    /** 管控分页计数基句（可选过滤片段经 {@link #filterStatus()}/{@link #filterCmdType()} 追加）。 */
    String countPage();

    /** 管控分页查询基句（不含 payload；排序分页片段经 {@link #pageOrderLimit()} 追加）。 */
    String selectPage();

    /** 可选过滤片段：状态（值参数绑定）。 */
    String filterStatus();

    /** 可选过滤片段：命令类型（值参数绑定）。 */
    String filterCmdType();

    /** 分页排序片段：id 倒序 + LIMIT/OFFSET（值参数绑定）。 */
    String pageOrderLimit();

    /**
     * MySQL 8 默认方言：从 {@code sql/mysql/async-command-mysql.properties} 加载语句
     * （SQL 配置外置——语句内容与索引注释见该文件，本类零 SQL 字符串）。
     * 缺键/缺文件在首次取语句时快速失败（IllegalStateException），不静默降级。
     */
    class MySql implements AsyncCommandSql {

        /** 方言配置路径（classpath 相对） */
        private static final String PATH = "sql/mysql/async-command-mysql.properties";

        /** 已加载语句（UTF-8；静态加载一次复用） */
        private static final Properties STATEMENTS = load();

        /**
         * 加载方言配置文件。
         *
         * @return 语句表
         * @throws IllegalStateException 文件缺失或读取失败（组件装配期暴露而非运行期）
         */
        private static Properties load() {
            Properties p = new Properties();
            try (java.io.InputStream in = MySql.class.getClassLoader().getResourceAsStream(PATH)) {
                if (in == null) {
                    throw new IllegalStateException("MySQL 方言配置缺失: " + PATH);
                }
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("MySQL 方言配置加载失败: " + PATH, e);
            }
            return p;
        }

        /**
         * 取语句并校验存在。
         *
         * @param key 语句 key（与方法名一致）
         * @return 语句原文（含 {table} 占位）
         */
        private static String stmt(String key) {
            String v = STATEMENTS.getProperty(key);
            if (v == null || v.isBlank()) {
                throw new IllegalStateException("MySQL 方言语句缺失: " + PATH + "#" + key);
            }
            return v;
        }

        @Override public String createTable() { return stmt("createTable"); }
        @Override public String insert() { return stmt("insert"); }
        @Override public String findById() { return stmt("findById"); }
        @Override public String countUnfinished() { return stmt("countUnfinished"); }
        @Override public String claim() { return stmt("claim"); }
        @Override public String findByToken() { return stmt("findByToken"); }
        @Override public String claimById() { return stmt("claimById"); }
        @Override public String reclaimStale() { return stmt("reclaimStale"); }
        @Override public String updateState() { return stmt("updateState"); }
        @Override public String countByStatus() { return stmt("countByStatus"); }
        @Override public String countBacklogDue() { return stmt("countBacklogDue"); }
        @Override public String selectRecent() { return stmt("selectRecent"); }
        @Override public String countPage() { return stmt("countPage"); }
        @Override public String selectPage() { return stmt("selectPage"); }
        @Override public String filterStatus() { return stmt("filter.status"); }
        @Override public String filterCmdType() { return stmt("filter.cmdType"); }
        @Override public String pageOrderLimit() { return stmt("page.orderLimit"); }
    }
}
