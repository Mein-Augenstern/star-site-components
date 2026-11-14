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
package com.xp.starsite.tuner.store;

import java.util.HashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 治理三表 SQL 方言抽象（SQL 配置外置铁律 D5）：Java 零 SQL 字符串——MySQL 默认方言
 * 语句在 {@code resources/dialect/mysql/tuner-mysql.xml}，{@link MySql} 只做安全加载
 * 与缺键校验。表名占位三枚：{presetTable}/{windowTable}/{auditTable}/{bindingTable}
 * （存储层渲染，均经白名单校验）；参数一律 {@code ?}。
 *
 * @author muyi
 * @date 2026/11/14
 */
public interface TunerSql {

    /**
     * 建预案表。
     *
     * @return DDL
     */
    String createPresetTable();

    /**
     * 建窗口表。
     *
     * @return DDL
     */
    String createWindowTable();

    /**
     * 建绑定表。
     *
     * @return DDL
     */
    String createBindingTable();

    /**
     * 建审计表。
     *
     * @return DDL
     */
    String createAuditTable();

    /**
     * upsert 预案。
     *
     * @return INSERT 语句
     */
    String upsertPreset();

    /**
     * 预案点查。
     *
     * @return SELECT 语句
     */
    String findPreset();

    /**
     * 全部预案。
     *
     * @return SELECT 语句
     */
    String listPresets();

    /**
     * upsert 绑定。
     *
     * @return INSERT 语句
     */
    String upsertBinding();

    /**
     * 绑定点查。
     *
     * @return SELECT 语句
     */
    String findBinding();

    /**
     * 绑定计数。
     *
     * @return SELECT 语句
     */
    String countBindings();

    /**
     * 新增窗口。
     *
     * @return INSERT 语句
     */
    String insertWindow();

    /**
     * 更新窗口。
     *
     * @return UPDATE 语句
     */
    String updateWindow();

    /**
     * 删除窗口。
     *
     * @return DELETE 语句
     */
    String removeWindow();

    /**
     * 窗口清单（? IS NULL OR pool_name=? 动态过滤——参数化非拼接）。
     *
     * @return SELECT 语句
     */
    String listWindows();

    /**
     * 追加审计。
     *
     * @return INSERT 语句
     */
    String appendAudit();

    /**
     * 最近审计。
     *
     * @return SELECT 语句
     */
    String recentAudits();

    /**
     * 配置版本戳。
     *
     * @return SELECT 语句
     */
    String versionStamp();

    /**
     * 审计 retention 第一步：圈到期行主键。
     *
     * @return SELECT 语句
     */
    String selectExpiredAuditIds();

    /**
     * 审计 retention 第二步：按主键集合删除。
     *
     * @return DELETE 语句
     */
    String deleteAuditByIds();

    /**
     * MySQL 默认方言（静态加载一次，缺键快速失败；拒绝 DOCTYPE/外部实体——家族同形）。
     */
    class MySql implements TunerSql {

        /** 方言配置路径 */
        private static final String PATH = "dialect/mysql/tuner-mysql.xml";

        /** 已加载语句 */
        private static final Map<String, String> STATEMENTS = load();

        /**
         * 加载方言配置文件。
         *
         * @return 语句表
         * @throws IllegalStateException 文件缺失/解析失败
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
         * @param key 语句 key
         * @return 语句原文
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
        public String createPresetTable() {
            return stmt("createPresetTable");
        }

        /** {@inheritDoc} */
        @Override
        public String createWindowTable() {
            return stmt("createWindowTable");
        }

        /** {@inheritDoc} */
        @Override
        public String createBindingTable() {
            return stmt("createBindingTable");
        }

        /** {@inheritDoc} */
        @Override
        public String createAuditTable() {
            return stmt("createAuditTable");
        }

        /** {@inheritDoc} */
        @Override
        public String upsertPreset() {
            return stmt("upsertPreset");
        }

        /** {@inheritDoc} */
        @Override
        public String findPreset() {
            return stmt("findPreset");
        }

        /** {@inheritDoc} */
        @Override
        public String listPresets() {
            return stmt("listPresets");
        }

        /** {@inheritDoc} */
        @Override
        public String upsertBinding() {
            return stmt("upsertBinding");
        }

        /** {@inheritDoc} */
        @Override
        public String findBinding() {
            return stmt("findBinding");
        }

        /** {@inheritDoc} */
        @Override
        public String countBindings() {
            return stmt("countBindings");
        }

        /** {@inheritDoc} */
        @Override
        public String insertWindow() {
            return stmt("insertWindow");
        }

        /** {@inheritDoc} */
        @Override
        public String updateWindow() {
            return stmt("updateWindow");
        }

        /** {@inheritDoc} */
        @Override
        public String removeWindow() {
            return stmt("removeWindow");
        }

        /** {@inheritDoc} */
        @Override
        public String listWindows() {
            return stmt("listWindows");
        }

        /** {@inheritDoc} */
        @Override
        public String appendAudit() {
            return stmt("appendAudit");
        }

        /** {@inheritDoc} */
        @Override
        public String recentAudits() {
            return stmt("recentAudits");
        }

        /** {@inheritDoc} */
        @Override
        public String versionStamp() {
            return stmt("versionStamp");
        }

        /** {@inheritDoc} */
        @Override
        public String selectExpiredAuditIds() {
            return stmt("selectExpiredAuditIds");
        }

        /** {@inheritDoc} */
        @Override
        public String deleteAuditByIds() {
            return stmt("deleteAuditByIds");
        }
    }
}
