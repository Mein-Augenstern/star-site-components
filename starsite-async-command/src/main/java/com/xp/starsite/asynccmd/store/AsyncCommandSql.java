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


/**
 * 命令表 SQL 方言抽象：把全部 SQL 语句从存储实现中外提——语句集中可见、
 * 方言可替换（MySQL 默认实现；适配其他数据库/ORM 时实现本接口并经
 * starter 的 @ConditionalOnMissingBean 让位注入即可）。
 * <p>
 * 设计约定：
 * <ul>
 *   <li><b>SQL 配置外置铁律</b>：Java 代码零 SQL 字符串——MySQL 默认方言的语句在
 *       {@code resources/dialect/mysql/async-command-mysql.xml}（{@code <statement id>}
 *       与本接口方法一一对应，CDATA 多行原生 SQL + note 属性注释用途），{@link MySql} 只做加载与缺键校验</li>
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

    /**
     * 建表语句（幂等 IF NOT EXISTS；{table} 为表名占位）。
     * <p>
     * 表结构语义（语句内容在 {@code dialect/mysql/async-command-mysql.xml}，生产 DDL
     * 见 {@code sql/mysql/async_command_ddl.sql}）：
     * <ul>
     *   <li>{@code id}：物理主键由 starsite-sequence 组件发号——<b>禁 AUTO_INCREMENT</b>
     *       （分布式发号全局有序，自增列在多写入点会撞号）</li>
     *   <li>索引三件套（均有明确查询锚点，防慢 SQL 门禁项）：
     *       {@code idx_status_next}（认领：状态+到期扫）、{@code idx_type_biz}
     *       （幂等计数：同类型同业务键）、{@code idx_claim_token}（认领取回：
     *       按令牌取本批，一批共用令牌故非唯一）</li>
     *   <li>{@code gmt_*} 标准时间列：悬挂回收（reclaimStale）以 gmt_modified 判死</li>
     * </ul>
     * 分片形态注记：分片键为 biz_key——提交/幂等带分片键精确路由单片；认领/回收
     * 不带分片键广播全分片（fan-out，1库×10表实装验证 0.2.2）；UPDATE..LIMIT 在
     * 分片中间件下不可用（ERROR 20046），回收走两步法语句。
     */
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

    /** 崩溃回收（旧形态，单表专用）：悬挂 RUNNING 回 PENDING（参数：threshold, limit）。分片形态用两步法。 */
    String reclaimStale();

    /** 崩溃回收两步法·第 1 步：选悬挂 id（参数：threshold, limit；SELECT..LIMIT 分片友好——广播后按片归并）。 */
    String selectStaleIds();

    /** 崩溃回收两步法·第 2 步：按 id IN 回收（占位符 {idPlaceholders} 由存储层按条数渲染，值全参数绑定）。 */
    String reclaimByIds();

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
     * MySQL 8 默认方言：从 {@code dialect/mysql/async-command-mysql.xml} 加载语句
     * （SQL 配置外置——语句内容与索引注释见该文件，本类零 SQL 字符串）。
     * 缺键/缺文件在首次取语句时快速失败（IllegalStateException），不静默降级。
     */
    class MySql implements AsyncCommandSql {

        /** 方言配置路径（classpath 相对） */
        private static final String PATH = "dialect/mysql/async-command-mysql.xml";

        /** 已加载语句（statement id → SQL；静态加载一次复用） */
        private static final java.util.Map<String, String> STATEMENTS = load();

        /**
         * 加载方言配置文件（安全 XML 解析：拒绝 DOCTYPE 与外部实体——配置属可信产物，
         * 但按不可信输入的防御基线处理）。
         *
         * @return 语句表（statement id → SQL 原文，已 trim）
         * @throws IllegalStateException 文件缺失/解析失败（组件装配期暴露而非运行期）
         */
        private static java.util.Map<String, String> load() {
            javax.xml.parsers.DocumentBuilderFactory factory =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);
            } catch (javax.xml.parsers.ParserConfigurationException e) {
                throw new IllegalStateException("XML 解析器安全特性设置失败: " + PATH, e);
            }
            try (java.io.InputStream in = MySql.class.getClassLoader().getResourceAsStream(PATH)) {
                if (in == null) {
                    throw new IllegalStateException("MySQL 方言配置缺失: " + PATH);
                }
                org.w3c.dom.Document doc = factory.newDocumentBuilder().parse(in);
                org.w3c.dom.NodeList nodes = doc.getElementsByTagName("statement");
                java.util.Map<String, String> stmts = new java.util.HashMap<>();
                for (int i = 0; i < nodes.getLength(); i++) {
                    org.w3c.dom.Element el = (org.w3c.dom.Element) nodes.item(i);
                    String sql = el.getTextContent().trim();
                    if (sql.isBlank()) {
                        throw new IllegalStateException("方言语句为空: " + PATH + "#" + el.getAttribute("id"));
                    }
                    stmts.put(el.getAttribute("id"), sql);
                }
                return stmts;
            } catch (Exception e) {
                throw new IllegalStateException("MySQL 方言配置加载失败: " + PATH, e);
            }
        }

        /**
         * 取语句并校验存在。
         *
         * @param key 语句 key（与方法名一致）
         * @return 语句原文（含 {table} 占位）
         */
        private static String stmt(String key) {
            String v = STATEMENTS.get(key);
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
        @Override public String selectStaleIds() { return stmt("selectStaleIds"); }
        @Override public String reclaimByIds() { return stmt("reclaimByIds"); }
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
