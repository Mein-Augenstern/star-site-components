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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 方言健全性 + 四载体条件对齐测试：QuotaSql.MySql 语句键全对齐；不变式条件
 * （占用+冻结+增量≤总量）与降额保护在 JDBC 方言 XML、MyBatis Mapper XML、
 * Redis Lua 三载体逐条互证——单载体漏改条件直接红（reviewer 第 5 尺；
 * memory 载体由 QuotaEngineTest 并发行为锁定）。
 *
 * @author muyi
 * @date 2026/11/14
 */
class QuotaSqlMySqlTest {

    /** 被测方言 */
    private final QuotaSql sql = new QuotaSql.MySql();

    /** 全部语句非空且带 {table} 占位（countAccounts 无 WHERE 也应含表名） */
    @Test
    void allStatementsPresent() {
        assertThat(sql.createTable()).contains("{table}").contains("CREATE TABLE");
        assertThat(sql.insert()).contains("{table}").contains("INSERT INTO");
        assertThat(sql.findByKey()).contains("{table}").contains("quota_key = ?");
        assertThat(sql.occupy()).contains("{table}");
        assertThat(sql.release()).contains("{table}");
        assertThat(sql.freeze()).contains("{table}");
        assertThat(sql.unfreeze()).contains("{table}");
        assertThat(sql.confirm()).contains("{table}");
        assertThat(sql.adjustTotal()).contains("{table}");
        assertThat(sql.countAccounts()).contains("{table}").contains("COUNT(*)");
    }

    /** 建表契约：无 AUTO_INCREMENT、uk 认领锚、三计数器列、gmt 两列 */
    @Test
    void createTableContract() {
        String ddl = sql.createTable();
        assertThat(ddl).doesNotContain("AUTO_INCREMENT");
        assertThat(ddl).contains("UNIQUE KEY uk_quota_key (quota_key)");
        assertThat(ddl).contains("total_amount").contains("occupied").contains("frozen");
        assertThat(ddl).contains("gmt_create").contains("gmt_modified");
    }

    /** 四载体条件对齐：不变式/充足性/降额保护逐分支互证（XML 字面量 vs Lua tonumber 比较） */
    @Test
    void conditionsAlignedAcrossCarriers() {
        String dialect = resource("src/main/resources/dialect/mysql/quota-mysql.xml");
        String mapper = resource(
                "src/main/resources/com/xp/starsite/quota/store/mybatis/QuotaAccountMapper.xml");
        // 不变式（occupy/freeze）：三载体逐条同在（XML 内 < 转义为 &lt;）
        assertThat(mapper).contains("occupied + frozen + #{amount} &lt;= total_amount")
                .as("MyBatis 载体不变式条件");
        assertThat(dialect).contains("occupied + frozen + ? <= total_amount")
                .as("JDBC 方言载体不变式条件（CDATA 内原文非转义）");
        assertThat(dialect).contains("occupied + frozen + ? <= total_amount");
        assertThat(resource("src/main/resources/sql/quota-occupy.lua"))
                .contains("occ + frz + tonumber(ARGV[1]) <= tonumber(t)");
        assertThat(resource("src/main/resources/sql/quota-freeze.lua"))
                .contains("occ + frz + tonumber(ARGV[1]) <= tonumber(t)");
        // 充足性（release/unfreeze/confirm）（XML 内 > 转义为 &gt;）
        assertThat(dialect).contains("occupied >= ?").contains("frozen >= ?");
        assertThat(mapper).contains("occupied &gt;= #{amount}").contains("frozen &gt;= #{amount}");
        assertThat(resource("src/main/resources/sql/quota-release.lua"))
                .contains("tonumber(occ) >= tonumber(ARGV[1])");
        assertThat(resource("src/main/resources/sql/quota-unfreeze.lua"))
                .contains("tonumber(frz) >= tonumber(ARGV[1])");
        // confirm 单语句双计数换位
        assertThat(dialect).contains("frozen = frozen - ?, occupied = occupied + ?");
        assertThat(mapper).contains("frozen = frozen - #{amount}, occupied = occupied + #{amount}");
        assertThat(resource("src/main/resources/sql/quota-confirm.lua"))
                .contains("'frozen', tonumber(frz) - tonumber(ARGV[1])")
                .contains("'occupied', occ + tonumber(ARGV[1])");
        // 降额保护（> 转义）
        assertThat(dialect).contains("total_amount + ? >= occupied + frozen");
        assertThat(mapper).contains("total_amount + #{delta} &gt;= occupied + frozen");
        assertThat(resource("src/main/resources/sql/quota-adjust.lua"))
                .contains("tonumber(t) + tonumber(ARGV[1]) >= occ + frz");
    }

    /** 占用/冻结语句条件占位参数计数（防方言语句改占位后绑定错位） */
    @Test
    void placeholderCounts() {
        assertThat(count(sql.occupy())).isEqualTo(3);   // SET 增量 + 键 + 不变式增量
        assertThat(count(sql.release())).isEqualTo(3);
        assertThat(count(sql.freeze())).isEqualTo(3);
        assertThat(count(sql.unfreeze())).isEqualTo(3);
        assertThat(count(sql.confirm())).isEqualTo(4);  // SET×2 + 键 + 条件
        assertThat(count(sql.adjustTotal())).isEqualTo(3);
        assertThat(count(sql.insert())).isEqualTo(4);   // id/键/总量/建户时刻
    }

    /**
     * 读模块内资源原文（测试工作目录=模块根）。
     *
     * @param rel 模块相对路径
     * @return 文件原文
     */
    private static String resource(String rel) {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(rel)),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读资源失败: " + rel, e);
        }
    }

    /** ? 占位计数 */
    private static long count(String stmt) {
        return stmt.chars().filter(c -> c == '?').count();
    }

    /** 并发冒烟（memory 形态在 sql 测试外的行为锁由引擎测试承担——此处锁 store 级别恰额语义） */
    @Test
    void memoryStoreExactBoundaryUnderConcurrency() throws Exception {
        com.xp.starsite.quota.store.memory.MemoryQuotaStore store =
                new com.xp.starsite.quota.store.memory.MemoryQuotaStore();
        store.init("k", 100);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                start.countDown();
                try {
                    start.await();
                    if (store.occupy("k", 10).isPresent()) {
                        ok.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // 计数断言兜底
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThat(ok.get()).isEqualTo(10);
        assertThat(store.find("k").get().getOccupied()).isEqualTo(100);
    }
}
