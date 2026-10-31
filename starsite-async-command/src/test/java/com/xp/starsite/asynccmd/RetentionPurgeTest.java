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
package com.xp.starsite.asynccmd;

import static org.assertj.core.api.Assertions.assertThat;

import com.xp.starsite.asynccmd.clock.TimeSource;
import com.xp.starsite.asynccmd.model.AsyncCommand;
import com.xp.starsite.asynccmd.store.InMemoryAsyncCommandStore;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * 终态行清理（retention，OBS-111）语义测试：loop 压测实证 SUCCESS/DEAD 行无限累积
 * （单日近 4000 行）——本特性按保留天数批量清理终态行。
 * <p>
 * 语义边界（必须逐条成立）：
 * <ul>
 *   <li>过期 SUCCESS/DEAD 被清</li>
 *   <li>未过期终态保留（对账窗口内不丢）</li>
 *   <li>PENDING/RUNNING 即使过期也<b>永不清理</b>（活命令与可认领命令不是垃圾）</li>
 *   <li>批量上限生效（大积压分多轮）</li>
 *   <li>调度器挂载低频触发（一小时窗口 + retentionEnabled 开关）</li>
 * </ul>
 *
 * @author muyi
 * @date 2026/10/31
 */
class RetentionPurgeTest {

    /** 基准时刻（过期判定确定性构造用） */
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 31, 12, 0);

    /** 可回拨假时钟（insert 会以 time.now() 落 gmt_modified——用时钟回拨构造过期态） */
    private static final class MutableClock implements TimeSource {
        LocalDateTime now = BASE;
        @Override public LocalDateTime now() { return now; }
    }

    /**
     * 三态混合场景：只有「过期的终态」被清，未过期终态与活命令全数保留。
     */
    @Test
    void 过期终态被清_其余全保留() {
        MutableClock clock = new MutableClock();
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore(clock);

        clock.now = BASE.minusDays(9);
        insert(store, 2, AsyncCommand.DEAD);                     // 过期死信 → 清
        clock.now = BASE.minusDays(8);
        insert(store, 1, AsyncCommand.SUCCESS);                  // 过期终态 → 清
        clock.now = BASE.minusDays(2);
        insert(store, 3, AsyncCommand.SUCCESS);                  // 未过期终态 → 留
        clock.now = BASE.minusDays(30);
        insert(store, 4, AsyncCommand.PENDING);                  // 过期但活 → 留
        insert(store, 5, AsyncCommand.RUNNING);                   // 过期但在跑 → 留
        clock.now = BASE;                                         // 回到基准执行清理

        int purged = store.purgeExpired(7, 500);

        assertThat(purged).isEqualTo(2);
        assertThat(store.findById(1)).isNull();
        assertThat(store.findById(2)).isNull();
        assertThat(store.findById(3)).isNotNull();
        assertThat(store.findById(4)).isNotNull();
        assertThat(store.findById(5)).isNotNull();
    }

    /**
     * 批量上限：过期 5 条但单轮限 2 → 只清 2 条（大积压分多轮消化，控长事务）。
     */
    @Test
    void 批量上限生效() {
        MutableClock clock = new MutableClock();
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore(clock);
        clock.now = BASE.minusDays(30);
        for (long i = 1; i <= 5; i++) {
            insert(store, i, AsyncCommand.SUCCESS);
        }
        clock.now = BASE;

        assertThat(store.purgeExpired(7, 2)).isEqualTo(2);
        assertThat(store.countByStatus(AsyncCommand.SUCCESS)).isEqualTo(3);
        assertThat(store.purgeExpired(7, 2)).isEqualTo(2);  // 第二轮继续消化
        assertThat(store.countByStatus(AsyncCommand.SUCCESS)).isEqualTo(1);
    }

    /**
     * 落命令辅助（gmt_modified 由存储按当前假时钟落——时钟回拨即构造过期态）。
     *
     * @param store  目标存储
     * @param id     命令 id
     * @param status 目标状态
     */
    private void insert(InMemoryAsyncCommandStore store, long id, String status) {
        AsyncCommand cmd = new AsyncCommand();
        cmd.setId(id);
        cmd.setCmdType("retention-test");
        cmd.setBizKey("k" + id);
        cmd.setStatus(status);
        cmd.setMaxRetry(1);
        cmd.setRetryCount(0);
        cmd.setNextRunAt(BASE);
        cmd.setOwner("test");
        store.insert(cmd);
    }
}
