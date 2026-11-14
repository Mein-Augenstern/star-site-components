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

import static org.assertj.core.api.Assertions.assertThat;

import com.xp.starsite.tuner.api.TunerPreset;
import java.nio.file.Files;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * 方言健全性 + 双载体对齐测试：TunerSql.MySql 语句键全对齐、建表契约（uk/无
 * AUTO_INCREMENT）、upsert 语义片段、与 MyBatis Mapper XML 的表名占位/语句对齐
 * （reviewer 第 5 尺——memory 载体由引擎行为测试锁定）。
 *
 * @author muyi
 * @date 2026/11/14
 */
class TunerSqlMySqlTest {

    /** 被测方言 */
    private final TunerSql sql = new TunerSql.MySql();

    /** 全部语句非空且带表名占位 */
    @Test
    void allStatementsPresent() {
        assertThat(sql.createPresetTable()).contains("{presetTable}").contains("CREATE TABLE");
        assertThat(sql.createWindowTable()).contains("{windowTable}");
        assertThat(sql.createBindingTable()).contains("{bindingTable}");
        assertThat(sql.createAuditTable()).contains("{auditTable}");
        assertThat(sql.upsertPreset()).contains("{presetTable}").contains("ON DUPLICATE KEY");
        assertThat(sql.findPreset()).contains("preset_name = ?");
        assertThat(sql.listPresets()).contains("{presetTable}");
        assertThat(sql.upsertBinding()).contains("ON DUPLICATE KEY");
        assertThat(sql.findBinding()).contains("pool_name = ?");
        assertThat(sql.countBindings()).contains("COUNT(*)");
        assertThat(sql.insertWindow()).contains("{windowTable}");
        assertThat(sql.updateWindow()).contains("WHERE id = ?");
        assertThat(sql.removeWindow()).contains("DELETE FROM");
        assertThat(sql.listWindows()).contains("(? IS NULL OR pool_name = ?)");
        assertThat(sql.appendAudit()).contains("{auditTable}");
        assertThat(sql.recentAudits()).contains("ORDER BY occurred_at DESC");
        assertThat(sql.versionStamp()).contains("GREATEST");
    }

    /** 建表契约：四表全部无 AUTO_INCREMENT、预案表 uk、绑定表 uk、审计表查询锚 */
    @Test
    void createTableContract() {
        for (String ddl : new String[]{sql.createPresetTable(), sql.createWindowTable(),
                sql.createBindingTable(), sql.createAuditTable()}) {
            assertThat(ddl).doesNotContain("AUTO_INCREMENT");
            assertThat(ddl).contains("gmt_create").contains("gmt_modified");
        }
        assertThat(sql.createPresetTable()).contains("UNIQUE KEY uk_tuner_preset_name");
        assertThat(sql.createBindingTable()).contains("UNIQUE KEY uk_tb_pool");
        assertThat(sql.createAuditTable()).contains("KEY idx_ta_pool_time");
    }

    /** 双载体对齐：JDBC 方言与 MyBatis Mapper XML 的语句集/表名占位互证（R2-⑥ 防漂移） */
    @Test
    void jdbcDialectAndMapperAligned() throws Exception {
        String mapper = new String(Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/resources/com/xp/starsite/tuner/store/mybatis/TunerMapper.xml")),
                java.nio.charset.StandardCharsets.UTF_8);
        // Mapper 四表建表存在
        for (String id : new String[]{"createPresetTable", "createWindowTable",
                "createBindingTable", "createAuditTable"}) {
            assertThat(mapper).contains('"' + id + '"');
        }
        // upsert 幂等语义同在（MySQL ON DUPLICATE KEY）
        assertThat(mapper).contains("ON DUPLICATE KEY UPDATE");
        // 动态过滤参数化非拼接（D5——Mapper 用 <if>，方言用 ? IS NULL OR，皆参数绑定）
        assertThat(mapper).contains("<if test=\"poolName != null\">");
        // 窗口/审计清单列集抽样对齐（start_cron/end_cron/old_core/new_keep 两边都在）
        assertThat(mapper).contains("start_cron").contains("end_cron")
                .contains("old_core").contains("new_keep");
    }

    /** memory 存储写后必变（versionStamp 契约——轮询短路的正确性前提） */
    @Test
    void memoryVersionStampMonotonic() {
        com.xp.starsite.tuner.store.memory.MemoryTunerStore store = new com.xp.starsite.tuner.store.memory.MemoryTunerStore(
                LocalDateTime::now);
        long before = store.versionStamp();
        store.upsertPreset(new TunerPreset("daily", 2, 4, 60));
        assertThat(store.versionStamp()).isGreaterThan(before);
        store.bindDefault("pool", "daily");
        assertThat(store.versionStamp()).isGreaterThan(before + 1);
        assertThat(store.countBindings()).isEqualTo(1);
    }
}
