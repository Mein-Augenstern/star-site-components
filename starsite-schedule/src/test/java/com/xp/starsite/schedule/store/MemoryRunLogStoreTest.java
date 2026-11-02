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

import com.xp.starsite.schedule.api.RunStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 内存流水存储测试：状态机往返 / 列表分列 / lastDone / 悬挂回收 / FIFO 上限。
 *
 * @author muyi
 * @date 2026/11/02
 */
class MemoryRunLogStoreTest {

    private static LocalDateTime t(int hour) {
        return LocalDateTime.of(2026, 11, 2, hour, 0, 0);
    }

    @Test
    void startFinishRoundTrip() {
        MemoryRunLogStore store = new MemoryRunLogStore();
        var log = store.start("t", "sch:t", t(8), t(8), "host:1");
        assertThat(log.getStatus()).isEqualTo(RunStatus.RUNNING);

        store.finish(log.getId(), RunStatus.DONE, 42L, null);
        var done = store.findById(log.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(RunStatus.DONE);
        assertThat(done.getDurationMs()).isEqualTo(42L);
        assertThat(done.getFinishedAt()).isNotNull();
    }

    @Test
    void recentByTaskOmitsErrorButDetailHasIt() {
        // 列表/详情分列铁律（D6）
        MemoryRunLogStore store = new MemoryRunLogStore();
        var log = store.start("t", "sch:t", t(8), t(8), "host:1");
        store.finish(log.getId(), RunStatus.FAILED, 1L, "IllegalStateException: boom");

        List<com.xp.starsite.schedule.api.RunLog> list = store.recentByTask("t", 10);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getError()).isNull();
        assertThat(store.findById(log.getId()).orElseThrow().getError()).contains("boom");
    }

    @Test
    void recentByTaskOrdersByIdDescAndFilters() {
        MemoryRunLogStore store = new MemoryRunLogStore();
        for (int i = 1; i <= 3; i++) {
            var log = store.start("t", "sch:t", t(7 + i), t(7 + i), "host:1");
            store.finish(log.getId(), RunStatus.DONE, 1L, null);
        }
        store.start("other", "sch:other", t(9), t(9), "host:1");

        List<com.xp.starsite.schedule.api.RunLog> list = store.recentByTask("t", 2);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).getTriggerTime()).isAfter(list.get(1).getTriggerTime());
        assertThat(store.recentByTask("t", 10)).hasSize(3);
    }

    @Test
    void lastDoneTriggerTimeFromNewestDone() {
        MemoryRunLogStore store = new MemoryRunLogStore();
        assertThat(store.lastDoneTriggerTime("t")).isEmpty();

        var a = store.start("t", "sch:t", t(8), t(8), "h:1");
        store.finish(a.getId(), RunStatus.DONE, 1L, null);
        var b = store.start("t", "sch:t", t(9), t(9), "h:1");
        store.finish(b.getId(), RunStatus.FAILED, 1L, null);  // 更晚但失败——不算 DONE
        var c = store.start("t", "sch:t", t(10), t(10), "h:1");
        store.finish(c.getId(), RunStatus.DONE, 1L, null);

        assertThat(store.lastDoneTriggerTime("t")).contains(t(10));
    }

    @Test
    void markStaleOnlyTouchesOldRunning() {
        MemoryRunLogStore store = new MemoryRunLogStore();
        var old = store.start("t", "sch:t", t(1), t(1), "h:1");       // 昨天 01:00 开始
        var fresh = store.start("t2", "sch:t2", t(11), t(11), "h:1");  // 11:00 开始（新）
        var done = store.start("t3", "sch:t3", t(1), t(1), "h:1");
        store.finish(done.getId(), RunStatus.DONE, 1L, null);

        int marked = store.markStaleRunning(t(9));  // 09:00 前开始的 RUNNING 判死

        assertThat(marked).isEqualTo(1);
        assertThat(store.findById(old.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.STALE);
        assertThat(store.findById(fresh.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(store.findById(done.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.DONE);
    }

    @Test
    void fifoCapEvictsOldest() {
        MemoryRunLogStore store = new MemoryRunLogStore();
        for (int i = 0; i < MemoryRunLogStore.MAX_ROWS + 100; i++) {
            store.start("t", "sch:t", t(8), t(8), "h:1");
        }
        assertThat(store.recentByTask("t", Integer.MAX_VALUE))
                .hasSize(MemoryRunLogStore.MAX_ROWS);
    }
}
