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
package com.xp.starsite.idempotent.store;

import java.util.HashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 幂等表 SQL 方言抽象（SQL 配置外置铁律 D5）：Java 零 SQL 字符串——MySQL 默认方言的
 * 语句在 {@code resources/dialect/mysql/idempotent-mysql.xml}（{@code <statement id>}
 * 与本接口方法一一对应，CDATA 承载多行原生 SQL），{@link MySql} 只做安全加载与缺键校验。
 *
 * <p>表名占位统一 {@code {table}}（存储层替换，表名经白名单校验防注入）；
 * 参数一律 {@code ?} 占位符。生产 DDL 见 {@code sql/mysql/idempotent_record_ddl.sql}。
 *
 * @author muyi
 * @date 2026/11/12
 */
public interface IdempotentSql {

    /**
     * 建表语句（幂等 IF NOT EXISTS）。
     *
     * @return 含 {table} 占位的 DDL
     */
    String createTable();

    /**
     * 认领插入（uk(scope,idem_key) 冲突即 duplicate——冲突判定在存储层异常翻译）。
     *
     * @return INSERT 语句
     */
    String insert();

    /**
     * 键点查详情（全列含 result/error 详情列）。
     *
     * @return SELECT 语句
     */
    String findByKey();

    /**
     * 条件回收（三合一：FAILED / expire_at 过期 / PROCESSING 停滞——原子重置 PROCESSING）。
     *
     * @return UPDATE 语句
     */
    String reclaim();

    /**
     * 落成功终态（守卫 status='PROCESSING' 前置）。
     *
     * @return UPDATE 语句
     */
    String complete();

    /**
     * 落失败终态（守卫 status='PROCESSING' 前置）。
     *
     * @return UPDATE 语句
     */
    String fail();

    /**
     * 按状态计数。
     *
     * @return SELECT 语句
     */
    String countByStatus();

    /**
     * 圈定过期行主键（第一步；第二步按主键集合删除——分片红线合规的两步法）。
     *
     * @return SELECT 语句
     */
    String selectExpiredIds();

    /**
     * 按主键集合删除（第二步；{idPlaceholders} 由存储层按选中条数渲染——占位符非值拼接）。
     *
     * @return DELETE 语句
     */
    String deleteByIds();

    /**
     * MySQL 默认方言：加载 {@code dialect/mysql/idempotent-mysql.xml}（静态一次），
     * 缺键/缺文件在首次取语句时快速失败（IllegalStateException），不静默降级。
     * 安全 XML 解析：拒绝 DOCTYPE 与外部实体——按不可信输入的防御基线处理
     * （与 async-command/schedule 同形）。
     */
    class MySql implements IdempotentSql {

        /** 方言配置路径（classpath 相对） */
        private static final String PATH = "dialect/mysql/idempotent-mysql.xml";

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
                    if (sql.trim().isEmpty()) {
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
            if (v == null || v.trim().isEmpty()) {
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
        public String reclaim() {
            return stmt("reclaim");
        }

        /** {@inheritDoc} */
        @Override
        public String complete() {
            return stmt("complete");
        }

        /** {@inheritDoc} */
        @Override
        public String fail() {
            return stmt("fail");
        }

        /** {@inheritDoc} */
        @Override
        public String countByStatus() {
            return stmt("countByStatus");
        }

        /** {@inheritDoc} */
        @Override
        public String selectExpiredIds() {
            return stmt("selectExpiredIds");
        }

        /** {@inheritDoc} */
        @Override
        public String deleteByIds() {
            return stmt("deleteByIds");
        }
    }
}
