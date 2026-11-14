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

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 四载体语义对齐测试（reviewer 第 5 尺：同一语义的多载体复写，行为差异必须有测试互证）：
 * 认领互斥 / 三合一回收 / 终态双条件守卫 在 JDBC 方言 XML、MyBatis Mapper XML、
 * Redis Lua 三处的条件片段必须逐条对齐——任何一处单独改条件（如漏掉停滞分支）
 * 在此直接红，而不是等真库形态诡异行为时排查。
 * memory 形态的等价条件由 MemoryIdempotentStoreTest.reclaimConditions 行为断言覆盖。
 *
 * @author muyi
 * @date 2026/11/14
 */
class CrossStoreConditionAlignmentTest {

    /** JDBC 方言语句（classpath 资源原文） */
    private static final String DIALECT = resource("src/main/resources/dialect/mysql/idempotent-mysql.xml");

    /** MyBatis Mapper XML 语句原文 */
    private static final String MAPPER = resource(
            "src/main/resources/com/xp/starsite/idempotent/store/mybatis/IdempotentRecordMapper.xml");

    /** Redis 回收脚本原文 */
    private static final String RECLAIM_LUA = resource("src/main/resources/sql/idempotent-reclaim.lua");

    /** Redis 成功终态脚本原文 */
    private static final String COMPLETE_LUA = resource("src/main/resources/sql/idempotent-complete.lua");

    /**
     * 三合一回收条件对齐：FAILED / expire_at 过期 / PROCESSING 停滞——三载体逐分支互证
     * （XML 载体 SQL 字面量 vs Lua 载体 ARGV 比较，措辞不同但分支必须同在）。
     */
    @Test
    void reclaimConditionsAlignedAcrossCarriers() {
        for (String carrier : new String[]{DIALECT, MAPPER}) {
            assertThat(carrier).contains("status = 'FAILED'");
            assertThat(carrier).contains("expire_at");
            assertThat(carrier).contains("status = 'PROCESSING' AND gmt_modified");
        }
        // Lua 载体：三分支同在（字节序比较形态）
        assertThat(RECLAIM_LUA).contains("status == 'FAILED'");
        assertThat(RECLAIM_LUA).contains("expireAt < ARGV[4]");
        assertThat(RECLAIM_LUA).contains("status == 'PROCESSING' and modified < ARGV[5]");
        // 回收重置换代语义（清 result/error + 新 expire_at）三载体同在
        assertThat(DIALECT).contains("result = NULL").contains("expire_at = ?");
        assertThat(MAPPER).contains("result").contains("expire_at = #{expireAt}");
        assertThat(RECLAIM_LUA).contains("HDEL").contains("'expire_at', ARGV[2]");
    }

    /**
     * 终态双条件守卫对齐：PROCESSING + 本代 expire_at（代际令牌）——四载体互证
     * （memory 载体由 MemoryIdempotentStoreTest.terminalGuardRejectsStaleGeneration 行为锁定）。
     */
    @Test
    void terminalGuardAlignedAcrossCarriers() {
        assertThat(DIALECT)
                .contains("AND status = 'PROCESSING' AND expire_at = ?")
                .as("JDBC 方言 complete/fail 双条件守卫");
        assertThat(MAPPER)
                .contains("AND status = 'PROCESSING' AND expire_at = #{claimExpireAt}")
                .as("MyBatis complete/fail 双条件守卫");
        assertThat(COMPLETE_LUA)
                .contains("== 'PROCESSING'")
                .contains("== ARGV[1]")
                .as("Lua 双条件守卫（PROCESSING + 本代 expire_at）");
    }

    /**
     * 读模块内资源原文（测试工作目录=模块根——Maven surefire 约定）。
     *
     * @param rel 模块相对路径
     * @return 文件原文
     */
    private static String resource(String rel) {
        try {
            return Files.readString(Path.of(rel));
        } catch (Exception e) {
            throw new IllegalStateException("对齐测试读资源失败: " + rel, e);
        }
    }
}
