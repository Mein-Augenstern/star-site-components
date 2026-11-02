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
package com.xp.starsite.schedule.store;

/**
 * 流水表 SQL 方言抽象（SQL 配置外置铁律 D5）：Java 零 SQL 字符串——MySQL 默认方言的
 * 语句在 {@code resources/dialect/mysql/schedule-mysql.xml}（{@code <statement id>}
 * 与本接口方法一一对应，CDATA 承载多行原生 SQL），{@link MySql} 只做加载与缺键校验。
 *
 * <p>表名占位统一 {@code {table}}（存储层替换，表名经白名单校验防注入）；
 * 参数一律 {@code ?} 占位符。生产 DDL 见 {@code sql/mysql/schedule_run_log_ddl.sql}。
 *
 * @author muyi
 * @date 2026/11/02
 */
public interface ScheduleRunLogSql {

    /**
     * 建表语句（幂等 IF NOT EXISTS）。
     *
     * @return 含 {table} 占位的 DDL
     */
    String createTable();

    /**
     * 插入流水行（id 由 sequence 组件预生成显式写入；status 终态由调用方传）。
     *
     * @return INSERT 语句
     */
    String insert();

    /**
     * 落终态（gmt_modified 取库时钟 ON UPDATE）。
     *
     * @return UPDATE 语句
     */
    String finish();

    /**
     * 悬挂回收（RUNNING→STALE，按 started_at 阈值）。
     *
     * @return UPDATE 语句
     */
    String markStaleRunning();

    /**
     * 最近流水（列表查询——不含 error 大列，分列铁律 D6）。
     *
     * @return SELECT 语句
     */
    String recentByTask();

    /**
     * 详情（全列含 error）。
     *
     * @return SELECT 语句
     */
    String findById();

    /**
     * 最近一次 DONE 的计划触发时刻（misfire 判定基准）。
     *
     * @return SELECT 语句
     */
    String lastDoneTriggerTime();

    /**
     * MySQL 默认方言：加载 {@code dialect/mysql/schedule-mysql.xml}（静态一次），
     * 缺键/缺文件在首次取语句时快速失败（IllegalStateException），不静默降级。
     */
    class MySql implements ScheduleRunLogSql {

        /** 方言配置路径（classpath 相对） */
        private static final String PATH = "dialect/mysql/schedule-mysql.xml";

        /** 已加载语句（statement id → SQL；静态加载一次复用） */
        private static final java.util.Map<String, String> STATEMENTS = load();

        /**
         * 加载方言配置文件（安全 XML 解析：拒绝 DOCTYPE 与外部实体——按不可信输入的
         * 防御基线处理，与 async-command 同形）。
         *
         * @return 语句表
         * @throws IllegalStateException 文件缺失/解析失败（装配期暴露而非运行期）
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
                throw new IllegalStateException("方言缺语句: " + PATH + "#" + key);
            }
            return v;
        }

        /** {@inheritDoc} */
        @Override
        public String createTable() {
            return stmt("createTable");
        }

        /** {@inheritDoc} */
        @Override
        public String insert() {
            return stmt("insert");
        }

        /** {@inheritDoc} */
        @Override
        public String finish() {
            return stmt("finish");
        }

        /** {@inheritDoc} */
        @Override
        public String markStaleRunning() {
            return stmt("markStaleRunning");
        }

        /** {@inheritDoc} */
        @Override
        public String recentByTask() {
            return stmt("recentByTask");
        }

        /** {@inheritDoc} */
        @Override
        public String findById() {
            return stmt("findById");
        }

        /** {@inheritDoc} */
        @Override
        public String lastDoneTriggerTime() {
            return stmt("lastDoneTriggerTime");
        }
    }
}
