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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * MySQL 方言配置健全性测试：sql/mysql/sequence-mysql.properties 与 SequenceSql
 * 接口的 key 对齐护栏——缺键提前到 CI 暴露；断言关键语义锚点
 * （LAST_INSERT_ID 技巧、upsert、uk 约束名随表名渲染）。
 *
 * @author muyi
 * @date 2026/10/30
 */
class SequenceSqlMySqlTest {

    /** 被测方言（静态加载一次） */
    private final SequenceSql sql = new SequenceSql.MySql();

    /** 全部语句可用且含 {table} 占位（selectLastInsertId 无表访问除外） */
    @Test
    void 全部语句可用且含表占位() {
        assertThat(sql.createTable()).contains("{table}");
        assertThat(sql.seedGlobalPool()).contains("{table}");
        assertThat(sql.takeFromGlobalPool()).contains("{table}");
        assertThat(sql.recordOwnership()).contains("{table}");
        assertThat(sql.resetGlobalPool()).contains("{table}");
        // selectLastInsertId 是会话函数无表访问——仅可用性
        assertThat(sql.selectLastInsertId()).containsIgnoringCase("LAST_INSERT_ID");
    }

    /** 关键语义锚点：原子取号技巧、审计 upsert、建表唯一键 */
    @Test
    void 关键语义锚点() {
        assertThat(sql.takeFromGlobalPool()).contains("LAST_INSERT_ID(worker_id + 1)");
        assertThat(sql.recordOwnership()).contains("ON DUPLICATE KEY UPDATE");
        assertThat(sql.createTable()).contains("uk_{table}_service_name");
        assertThat(sql.seedGlobalPool()).startsWith("INSERT IGNORE");
    }
}
