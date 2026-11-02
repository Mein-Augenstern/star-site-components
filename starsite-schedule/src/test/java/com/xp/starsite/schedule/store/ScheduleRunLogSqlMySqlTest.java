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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * MySQL 方言配置加载测试：statement id 与接口方法一一对应、非空、含 {table} 占位
 * （缺键会在装配期 IllegalStateException——本测试在 CI 就把它暴露成断言失败）。
 *
 * @author muyi
 * @date 2026/11/02
 */
class ScheduleRunLogSqlMySqlTest {

    @Test
    void allStatementsLoadWithTablePlaceholder() {
        ScheduleRunLogSql sql = new ScheduleRunLogSql.MySql();
        // 建表语句用 {table} 占位（表名由存储层白名单校验后渲染——字面量只出现在 DDL 交付文件）
        assertThat(sql.createTable()).contains("{table}").containsIgnoringCase("CREATE TABLE IF NOT EXISTS");
        assertThat(sql.insert()).contains("{table}").containsIgnoringCase("INSERT");
        assertThat(sql.finish()).contains("{table}").containsIgnoringCase("UPDATE");
        assertThat(sql.markStaleRunning()).contains("{table}").contains("'STALE'");
        // 列表分列：recentByTask 不含 error 列，findById 含（D6 铁律的语句级体现）
        assertThat(sql.recentByTask()).contains("{table}").doesNotContain("error");
        assertThat(sql.findById()).contains("{table}").contains("error");
        assertThat(sql.lastDoneTriggerTime()).contains("{table}").contains("'DONE'");
    }

    @Test
    void createTableIsIdempotent() {
        assertThat(new ScheduleRunLogSql.MySql().createTable())
                .containsIgnoringCase("CREATE TABLE IF NOT EXISTS");
    }
}
