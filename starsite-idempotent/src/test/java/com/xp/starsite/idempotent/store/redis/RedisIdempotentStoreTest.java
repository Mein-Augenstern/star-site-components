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
package com.xp.starsite.idempotent.store.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xp.starsite.idempotent.api.IdempotentStatus;
import com.xp.starsite.idempotent.clock.TimeSource;
import com.xp.starsite.idempotent.store.ClaimOutcome;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis 存储单测（Mockito 桩模板——与 RedisLockClientTest 同手法）：脚本内容断言 +
 * 键布局 + Lua 返回值语义翻译 + hash 解析 + 观测降级。真实 Redis 下的端到端语义
 * 由引擎测试（store 无关）与表形态等价断言共同背书。
 *
 * @author muyi
 * @date 2026/11/13
 */
class RedisIdempotentStoreTest {

    /** Redis 模板桩 */
    private StringRedisTemplate redis;

    /** hash 操作桩（find 通道） */
    private HashOperations<String, String, String> hashOps;

    /** 被测存储 */
    private RedisIdempotentStore store;

    /** 基准时刻（假时钟固定——TTL 与 ISO 参数确定性断言） */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 13, 10, 0, 0);

    /** 窗口边界（NOW + 24h） */
    private static final LocalDateTime EXPIRE = NOW.plusHours(24);

    /** 桩装配 */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        hashOps = mock(HashOperations.class);
        // opsForHash 为泛型方法——桩需显式见证 <String,String> 与生产代码同形态
        when(redis.<String, String>opsForHash()).thenReturn(hashOps);
        TimeSource clock = () -> NOW;
        store = new RedisIdempotentStore(redis, "starsite:idem:", clock);
    }

    /** 认领：脚本命中返回 1——claimed；键布局 = 前缀+scope+分隔符+key；ARGV 顺序与脚本契约一致 */
    @Test
    void claimInvokesScriptWithKeyAndArgs() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(1L);

        ClaimOutcome outcome = store.tryClaim("order", "o-1", EXPIRE, "h:1");

        assertThat(outcome.claimed()).isTrue();
        assertThat(outcome.record().getStatus()).isEqualTo(IdempotentStatus.PROCESSING);
        assertThat(outcome.record().getId()).isZero(); // redis 形态无 DB 主键
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<DefaultRedisScript> script = ArgumentCaptor.forClass(DefaultRedisScript.class);
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(script.capture(), keys.capture(), any(), any(), any(), any());
        assertThat(script.getValue().getScriptAsString())
                .contains("EXISTS").contains("HSET").contains("PEXPIRE");
        assertThat(keys.getValue()).containsExactly("starsite:idem:order\u0001o-1");
    }

    /** 认领：脚本返回 0——duplicate（他方持有） */
    @Test
    void claimDuplicateOnScriptZero() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(0L);
        assertThat(store.tryClaim("order", "o-1", EXPIRE, "h:1").claimed()).isFalse();
    }

    /** 键解析：hash 六字段 → 记录全字段（ISO 串往返无损）；空 hash → empty */
    @Test
    void findParsesHashFields() {
        Map<String, String> entries = new HashMap<>();
        entries.put("status", "DONE");
        entries.put("result", "{\"ok\":true}");
        entries.put("expire_at", "2026-11-14T10:00:00.000");
        entries.put("host", "h:9");
        entries.put("gmt_modified", "2026-11-13T10:00:01.000");
        when(hashOps.entries("starsite:idem:order\u0001o-1")).thenReturn(entries);

        var record = store.find("order", "o-1").orElseThrow();
        assertThat(record.getStatus()).isEqualTo(IdempotentStatus.DONE);
        assertThat(record.getResult()).isEqualTo("{\"ok\":true}");
        assertThat(record.getExpireAt()).isEqualTo(LocalDateTime.of(2026, 11, 14, 10, 0, 0));
        assertThat(record.getHost()).isEqualTo("h:9");
        assertThat(record.getGmtModified()).isEqualTo(LocalDateTime.of(2026, 11, 13, 10, 0, 1));

        when(hashOps.entries("starsite:idem:order\u0001absent")).thenReturn(Map.of());
        assertThat(store.find("order", "absent")).isEmpty();
    }

    /** 回收：1 → 重读记录；0（不可回收）与 -1（键遗忘）统一 empty */
    @Test
    void reclaimTranslatesScriptResults() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(),
                any(), any(), any(), any(), any())).thenReturn(1L);
        when(hashOps.entries("starsite:idem:order\u0001o-1")).thenReturn(Map.of(
                "status", "PROCESSING",
                "expire_at", "2026-11-14T10:00:00.000",
                "host", "h:2",
                "gmt_modified", "2026-11-13T10:00:00.000"));
        assertThat(store.tryReclaim("order", "o-1", EXPIRE, NOW, NOW.minusSeconds(120), "h:2"))
                .isPresent();

        when(redis.execute(any(DefaultRedisScript.class), anyList(),
                any(), any(), any(), any(), any())).thenReturn(0L);
        assertThat(store.tryReclaim("order", "o-1", EXPIRE, NOW, NOW.minusSeconds(120), "h:2"))
                .isEmpty();

        when(redis.execute(any(DefaultRedisScript.class), anyList(),
                any(), any(), any(), any(), any())).thenReturn(-1L);
        assertThat(store.tryReclaim("order", "o-1", EXPIRE, NOW, NOW.minusSeconds(120), "h:2"))
                .isEmpty();
    }

    /** 成功终态：脚本含双条件守卫（PROCESSING + expire_at 代际令牌）；null 载荷走删除标志 */
    @Test
    void completeGuardScriptAndNullResultFlag() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(1L);

        assertThat(store.complete("order", "o-1", EXPIRE, "payload")).isTrue();
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<DefaultRedisScript> script = ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redis).execute(script.capture(), anyList(), any(), any(), any(), any());
        assertThat(script.getValue().getScriptAsString())
                .contains("PROCESSING").contains("expire_at")
                .contains("HSET").contains("DONE");

        // void 语义：has-result 标志 0（脚本走 HDEL result 分支）
        org.mockito.Mockito.clearInvocations(redis);
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(1L);
        assertThat(store.complete("order", "o-1", EXPIRE, null)).isTrue();
        verify(redis).execute(any(DefaultRedisScript.class), anyList(),
                any(), any(), org.mockito.ArgumentMatchers.eq("0"), any());

        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(0L);
        assertThat(store.complete("order", "o-1", EXPIRE, "late")).isFalse(); // 已换代弃写
    }

    /** 失败终态：守卫脚本 + 摘要写入 */
    @Test
    void failGuardScript() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(1L);
        assertThat(store.fail("order", "o-1", EXPIRE, "boom")).isTrue();

        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(0L);
        assertThat(store.fail("order", "o-1", EXPIRE, "late")).isFalse();
    }

    /** 观测降级：countByStatus -1（无全库计数）+ purgeExpired 0（TTL 原生过期） */
    @Test
    void observationDegraded() {
        assertThat(store.countByStatus(IdempotentStatus.DONE)).isEqualTo(-1);
        assertThat(store.purgeExpired(100, NOW)).isZero();
    }

    /** 键前缀缺省与 scope 归一（null scope → 空段；null 前缀 → 缺省 starsite:idem:） */
    @Test
    void keyPrefixAndScopeDefaults() {
        RedisIdempotentStore bare = new RedisIdempotentStore(redis, null);
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(0L);
        bare.tryClaim(null, "k", EXPIRE, "h");
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(DefaultRedisScript.class), keys.capture(), any(), any(), any(), any());
        assertThat(keys.getValue()).containsExactly("starsite:idem:\u0001k");
    }
}
