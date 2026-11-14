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
package com.xp.starsite.quota.store;

import java.util.HashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 配额表 SQL 方言抽象（SQL 配置外置铁律 D5）：Java 零 SQL 字符串——MySQL 默认方言的
 * 语句在 {@code resources/dialect/mysql/quota-mysql.xml}（{@code <statement id>}
 * 与本接口方法一一对应，CDATA 承载多行原生 SQL），{@link MySql} 只做安全加载与缺键校验。
 *
 * <p>表名占位统一 {@code {table}}（存储层替换，表名经白名单校验防注入）；
 * 参数一律 {@code ?} 占位符。生产 DDL 见 {@code sql/mysql/quota_account_ddl.sql}。
 *
 * @author muyi
 * @date 2026/11/14
 */
public interface QuotaSql {

    /**
     * 建表语句（幂等 IF NOT EXISTS）。
     *
     * @return 含 {table} 占位的 DDL
     */
    String createTable();

    /**
     * 建户插入（uk 冲突=已存在，存储层翻译为"保留现量返回账面"）。
     *
     * @return INSERT 语句
     */
    String insert();

    /**
     * 键点查账面。
     *
     * @return SELECT 语句
     */
    String findByKey();

    /**
     * 占用（条件 UPDATE：不变式在 WHERE 内）。
     *
     * @return UPDATE 语句
     */
    String occupy();

    /**
     * 释放占用。
     *
     * @return UPDATE 语句
     */
    String release();

    /**
     * 冻结。
     *
     * @return UPDATE 语句
     */
    String freeze();

    /**
     * 解冻。
     *
     * @return UPDATE 语句
     */
    String unfreeze();

    /**
     * 冻结转占用（单语句双计数换位）。
     *
     * @return UPDATE 语句
     */
    String confirm();

    /**
     * 调整总量（降额保护条件在 WHERE）。
     *
     * @return UPDATE 语句
     */
    String adjustTotal();

    /**
     * 账户计数（健康端点）。
     *
     * @return SELECT 语句
     */
    String countAccounts();

    /**
     * MySQL 默认方言：加载 {@code dialect/mysql/quota-mysql.xml}（静态一次），
     * 缺键/缺文件快速失败。安全 XML 解析：拒绝 DOCTYPE 与外部实体（家族同形）。
     */
    class MySql implements QuotaSql {

        /** 方言配置路径（classpath 相对） */
        private static final String PATH = "dialect/mysql/quota-mysql.xml";

        /** 已加载语句（statement id → SQL；静态加载一次复用） */
        private static final Map<String, String> STATEMENTS = load();

        /**
         * 加载方言配置文件。
         *
         * @return 语句表
         * @throws IllegalStateException 文件缺失/解析失败（装配期暴露而非运行期）
         */
        private static Map<String, String> load() {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
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
                Document doc = factory.newDocumentBuilder().parse(in);
                NodeList nodes = doc.getElementsByTagName("statement");
                Map<String, String> stmts = new HashMap<>();
                for (int i = 0; i < nodes.getLength(); i++) {
                    Element el = (Element) nodes.item(i);
                    String sql = el.getTextContent().trim();
                    if (sql.isBlank()) {
                        throw new IllegalStateException("方言语句为空: " + PATH + "#" + el.getAttribute("id"));
                    }
                    stmts.put(el.getAttribute("id"), sql);
                }
                return stmts;
            } catch (IllegalStateException e) {
                throw e;
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
        public String findByKey() {
            return stmt("findByKey");
        }

        /** {@inheritDoc} */
        @Override
        public String occupy() {
            return stmt("occupy");
        }

        /** {@inheritDoc} */
        @Override
        public String release() {
            return stmt("release");
        }

        /** {@inheritDoc} */
        @Override
        public String freeze() {
            return stmt("freeze");
        }

        /** {@inheritDoc} */
        @Override
        public String unfreeze() {
            return stmt("unfreeze");
        }

        /** {@inheritDoc} */
        @Override
        public String confirm() {
            return stmt("confirm");
        }

        /** {@inheritDoc} */
        @Override
        public String adjustTotal() {
            return stmt("adjustTotal");
        }

        /** {@inheritDoc} */
        @Override
        public String countAccounts() {
            return stmt("countAccounts");
        }
    }
}
