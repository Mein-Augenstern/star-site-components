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

import static org.assertj.core.api.Assertions.assertThat;

import com.xp.starsite.idempotent.api.IdempotentStatus;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 方言配置健全性测试：IdempotentSql.MySql 的语句键与接口方法全对齐（缺键提前到 CI 暴露），
 * 关键语句语义片段存在（uk 认领 / 三合一回收 / 双条件守卫 / 两步清理）——
 * 与 async-command/schedule 的 SqlMySqlTest 同形。
 *
 * @author muyi
 * @date 2026/11/12
 */
class IdempotentSqlMySqlTest {

    /** 被测方言 */
    private final IdempotentSql sql = new IdempotentSql.MySql();

    /** 全部语句非空且带 {table} 占位（除 deleteByIds 带 {idPlaceholders}） */
    @Test
    void allStatementsPresent() {
        assertThat(sql.createTable()).contains("{table}").contains("CREATE TABLE");
        assertThat(sql.insert()).contains("{table}").contains("INSERT INTO");
        assertThat(sql.findByKey()).contains("{table}");
        assertThat(sql.reclaim()).contains("{table}");
        assertThat(sql.complete()).contains("{table}");
        assertThat(sql.fail()).contains("{table}");
        assertThat(sql.countByStatus()).contains("{table}");
        assertThat(sql.selectExpiredIds()).contains("{table}").contains("LIMIT ?");
        assertThat(sql.deleteByIds()).contains("{table}").contains("{idPlaceholders}");
    }

    /** 建表语义铁律：无 AUTO_INCREMENT、uk 认领锚、expire 索引、契约列全在 */
    @Test
    void createTableContract() {
        String ddl = sql.createTable();
        assertThat(ddl).doesNotContain("AUTO_INCREMENT");
        assertThat(ddl).contains("UNIQUE KEY uk_idem_scope_key (scope, idem_key)");
        assertThat(ddl).contains("KEY idx_idem_expire (expire_at)");
        assertThat(ddl).contains("gmt_create").contains("gmt_modified");
        for (String col : new String[]{"id", "scope", "idem_key", "status", "result", "error",
                "expire_at", "finished_at", "host"}) {
            assertThat(ddl).contains(col);
        }
    }

    /** 认领/回收/终态守卫语义片段：参数全占位绑定（无值拼接）、三合一条件、双条件守卫 */
    @Test
    void semanticFragments() {
        // 认领插入参数绑定（6 业务占位 + gmt_create 取库时钟 CURRENT_TIMESTAMP）
        assertThat(countPlaceholders(sql.insert())).isEqualTo(6);
        // 回收三合一条件 + 全占位
        String reclaim = sql.reclaim();
        assertThat(reclaim).contains("status = 'FAILED'").contains("expire_at < ?")
                .contains("status = 'PROCESSING' AND gmt_modified < ?");
        assertThat(countPlaceholders(reclaim)).isEqualTo(6);
        // 终态双条件守卫（代际令牌）
        assertThat(sql.complete()).contains("status = 'PROCESSING' AND expire_at = ?");
        assertThat(sql.fail()).contains("status = 'PROCESSING' AND expire_at = ?");
        // 清理两步：圈主键 + IN 删除
        assertThat(sql.selectExpiredIds()).contains("expire_at < ? LIMIT ?");
        assertThat(sql.deleteByIds()).contains("WHERE id IN ({idPlaceholders})");
    }

    /** 占位符计数（? 计数——参数绑定完备性） */
    private static long countPlaceholders(String stmt) {
        java.util.regex.Matcher m = Pattern.compile("\\?").matcher(stmt);
        long count = 0;
        while (m.find()) {
            count++;
        }
        return count;
    }

    /** 状态枚举与方言字面量对齐（fromCode 可解析 DDL 注释口径的三态） */
    @Test
    void statusEnumAligned() {
        assertThat(IdempotentStatus.fromCode("PROCESSING")).isEqualTo(IdempotentStatus.PROCESSING);
        assertThat(IdempotentStatus.fromCode("DONE")).isEqualTo(IdempotentStatus.DONE);
        assertThat(IdempotentStatus.fromCode("FAILED")).isEqualTo(IdempotentStatus.FAILED);
    }
}
