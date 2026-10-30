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

import com.xp.starsite.asynccmd.model.AsyncCommand;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 内存存储直测（此前只经调度器间接触达）：快照隔离、令牌认领、悬挂回收、
 * 列表/详情分列（payload 大列不进列表——与表形态显式列清单等价）。
 *
 * @author muyi
 * @date 2026/10/30
 */
class InMemoryAsyncCommandStoreTest {

    /** 测试 id 发号（模拟 sequence 组件） */
    private final AtomicLong ids = new AtomicLong(0);

    /** 提交一条命令并返回存储内快照 */
    private AsyncCommand submit(InMemoryAsyncCommandStore store, String type, String key, String payload) {
        AsyncCommand cmd = new AsyncCommand();
        cmd.setCmdType(type);
        cmd.setBizKey(key);
        cmd.setPayload(payload);
        cmd.setStatus(AsyncCommand.PENDING);
        cmd.setMaxRetry(3);
        cmd.setRetryCount(0);
        store.insert(cmd);
        return cmd;
    }

    /** 快照隔离：findById 返回副本，改副本不污染存储行 */
    @Test
    void 快照隔离_外部修改不污染存储() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommand cmd = submit(store, "t", "k1", "{}");

        AsyncCommand got = store.findById(cmd.getId());
        got.setStatus(AsyncCommand.SUCCESS);
        got.setPayload("tampered");

        assertThat(store.findById(cmd.getId()).getStatus()).isEqualTo(AsyncCommand.PENDING);
        assertThat(store.findById(cmd.getId()).getPayload()).isEqualTo("{}");
    }

    /** 列表/详情分列（与表形态等价）：selectRecent/selectPage 不携带 payload，findById 全量 */
    @Test
    void 列表不携带payload_详情全量() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        AsyncCommand a = submit(store, "t", "k1", "{\"big\":\"payload-64kb\"}");
        submit(store, "t", "k2", "{}");

        assertThat(store.selectRecent(10))
                .allSatisfy(c -> assertThat(c.getPayload()).isNull())
                .hasSize(2);
        assertThat(store.selectPage(null, null, 0, 10))
                .allSatisfy(c -> assertThat(c.getPayload()).isNull());
        assertThat(store.findById(a.getId()).getPayload()).isEqualTo("{\"big\":\"payload-64kb\"}");
    }

    /** 令牌认领互斥：认领后他方令牌取不到同批命令 */
    @Test
    void 令牌认领_批次互斥() {
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore();
        submit(store, "t", "k1", "{}");
        submit(store, "t", "k2", "{}");

        assertThat(store.claim("tok-a", 1, LocalDateTime.now())).isEqualTo(1);
        assertThat(store.claim("tok-b", 5, LocalDateTime.now())).isEqualTo(1); // 只剩 1 条待认领
        List<Long> idsA = store.findByToken("tok-a").stream().map(AsyncCommand::getId).toList();
        List<Long> idsB = store.findByToken("tok-b").stream().map(AsyncCommand::getId).toList();
        assertThat(idsA).doesNotContainAnyElementsOf(idsB);
    }

    /** 悬挂回收：RUNNING 超阈值回 PENDING 清令牌（到期判定用注入时钟） */
    @Test
    void 悬挂回收_阈值可注入() {
        java.time.LocalDateTime base = java.time.LocalDateTime.of(2026, 10, 30, 12, 0, 0);
        java.util.concurrent.atomic.AtomicReference<java.time.LocalDateTime> now =
                new java.util.concurrent.atomic.AtomicReference<>(base);
        InMemoryAsyncCommandStore store = new InMemoryAsyncCommandStore(() -> now.get());
        AsyncCommand cmd = submit(store, "t", "k1", "{}");
        store.claimById(cmd.getId(), "dead-owner");

        now.set(base.plusSeconds(301));  // 推进假时钟越过 300s 阈值
        assertThat(store.reclaimStale(300, 10)).isEqualTo(1);
        assertThat(store.countByStatus("PENDING")).isEqualTo(1);
        assertThat(store.findByToken("dead-owner")).isEmpty();
    }
}
