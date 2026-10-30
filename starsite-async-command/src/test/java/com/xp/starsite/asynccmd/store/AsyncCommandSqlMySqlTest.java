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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * MySQL 方言配置健全性测试：sql/mysql/async-command-mysql.properties 与
 * AsyncCommandSql 接口的 key 对齐护栏——缺键会在运行期快速失败，
 * 本测试把该暴露提前到 CI，并断言语句形态（{table} 占位/过滤片段前缀）。
 *
 * @author muyi
 * @date 2026/10/30
 */
class AsyncCommandSqlMySqlTest {

    /** 被测方言（静态加载一次，全部方法即全部 key 的健全性检查） */
    private final AsyncCommandSql sql = new AsyncCommandSql.MySql();

    /** 全部语句方法可用且含 {table} 占位（selectLastInsertId 类无表语句除外——本方言全部含表） */
    @Test
    void 全部语句可用且含表占位() {
        java.util.List<String> stmts = java.util.List.of(
                sql.createTable(), sql.insert(), sql.findById(), sql.countUnfinished(),
                sql.claim(), sql.findByToken(), sql.claimById(), sql.reclaimStale(),
                sql.updateState(), sql.countByStatus(), sql.countBacklogDue(),
                sql.selectRecent(), sql.countPage(), sql.selectPage());
        assertThat(stmts).allSatisfy(stmt -> {
            assertThat(stmt).contains("{table}");
            assertThat(stmt).doesNotContain("\n");
        });
    }

    /** 过滤/分页片段形态：过滤以 AND 起头、分页以 ORDER BY 起头且值全参数绑定 */
    @Test
    void 过滤与分页片段形态() {
        assertThat(sql.filterStatus()).startsWith("AND status = ?");
        assertThat(sql.filterCmdType()).startsWith("AND cmd_type = ?");
        assertThat(sql.pageOrderLimit()).startsWith("ORDER BY id DESC LIMIT ? OFFSET ?");
    }

    /** 列表/详情分列：列表语句不含 payload 列，详情全列（*） */
    @Test
    void 列表分列口径() {
        assertThat(sql.selectRecent()).doesNotContain("payload");
        assertThat(sql.selectPage()).doesNotContain("payload");
        assertThat(sql.findById()).contains("SELECT *");
        assertThat(sql.findByToken()).contains("SELECT *");
    }
}
