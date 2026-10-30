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

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * sequence 表 SQL 方言抽象：把全部租约 SQL 语句从分配器实现中外提——
 * 语句集中可见、方言可替换（MySQL 默认实现；适配其他数据库/ORM 时实现本接口
 * 经 starter 让位注入即可，MyBatis 迁移时各语句可逐条对照搬入 mapper XML）。
 * <p>
 * 设计约定：
 * <ul>
 *   <li><b>SQL 配置外置铁律</b>：Java 代码零 SQL 字符串——MySQL 默认方言的语句在
 *       {@code resources/sql/mysql/sequence-mysql.properties}（key 与本接口方法
 *       一一对应），{@link MySql} 只做加载与缺键校验</li>
 *   <li>表名占位统一为 {@code {table}}（由分配器替换，表名经白名单校验防注入）</li>
 *   <li>参数一律 {@code ?} 占位符，值由调用方绑定——语句本身不含业务值</li>
 * </ul>
 *
 * @author muyi
 * @date 2026/10/25
 */
public interface SequenceSql {

    /** 建表语句（幂等 IF NOT EXISTS；表规范：id 物理主键 + uk service_name + gmt_*）。 */
    String createTable();

    /** 播种全局池计数行（参数：GLOBAL_POOL_KEY, 'pool'；__global__ 行 id=1 自举保留）。 */
    String seedGlobalPool();

    /** 原子取号：LAST_INSERT_ID 技巧带回自增值（参数：instanceTag, GLOBAL_POOL_KEY）。 */
    String takeFromGlobalPool();

    /** 会话内取回上次 LAST_INSERT_ID 值（无参）。 */
    String selectLastInsertId();

    /** 记录/更新服务实例占用审计行（参数：id, serviceName, workerId, instanceTag——upsert 锚 service_name 唯一键）。 */
    String recordOwnership();

    /** 回绕重置全局池（参数：resetTag, GLOBAL_POOL_KEY）。 */
    String resetGlobalPool();

    /**
     * MySQL 8 默认方言：从 {@code sql/mysql/sequence-mysql.properties} 加载语句
     * （SQL 配置外置——语句内容与索引注释见该文件，本类零 SQL 字符串）。
     * 缺键/缺文件在首次取语句时快速失败，不静默降级。
     */
    class MySql implements SequenceSql {

        /** 方言配置路径（classpath 相对） */
        private static final String PATH = "sql/mysql/sequence-mysql.properties";

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
        @Override public String seedGlobalPool() { return stmt("seedGlobalPool"); }
        @Override public String takeFromGlobalPool() { return stmt("takeFromGlobalPool"); }
        @Override public String selectLastInsertId() { return stmt("selectLastInsertId"); }
        @Override public String recordOwnership() { return stmt("recordOwnership"); }
        @Override public String resetGlobalPool() { return stmt("resetGlobalPool"); }
    }
}
