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
package com.xp.starsite.quota.store.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis 存储单测（Mockito 桩模板——RedisLockClientTest 同手法）：脚本内容契约 +
 * 键布局 + 三态结果翻译（1=成功/0=条件不中/-1=键不存在）+ hash 解析 + 观测降级。
 *
 * @author muyi
 * @date 2026/11/14
 */
class RedisQuotaStoreTest {

    /** Redis 模板桩 */
    private StringRedisTemplate redis;

    /** hash 操作桩（find 通道） */
    private HashOperations<String, String, String> hashOps;

    /** 被测存储 */
    private RedisQuotaStore store;

    /** 桩装配 */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        hashOps = mock(HashOperations.class);
        when(redis.<String, String>opsForHash()).thenReturn(hashOps);
        store = new RedisQuotaStore(redis, "starsite:quota:");
    }

    /** 建户：脚本命中建新户（total 落库零计数）；脚本 0=已存在 → 现量返回 */
    @Test
    void initScriptAndIdempotent() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any())).thenReturn(1L);
        assertThat(store.init("credit:A", 1000))
                .extracting("total", "occupied", "frozen")
                .containsExactly(1000L, 0L, 0L);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<DefaultRedisScript> script = ArgumentCaptor.forClass(DefaultRedisScript.class);
        ArgumentCaptor<java.util.List<String>> keys = ArgumentCaptor.forClass(java.util.List.class);
        verify(redis).execute(script.capture(), keys.capture(), any());
        assertThat(script.getValue().getScriptAsString()).contains("EXISTS").contains("HSET");
        assertThat(keys.getValue()).containsExactly("starsite:quota:credit:A");

        // 0=已存在 → find 通道返回现量
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any())).thenReturn(0L);
        when(hashOps.entries("starsite:quota:credit:A")).thenReturn(new java.util.HashMap<String, String>() {{
            put("total", "1000");
            put("occupied", "200");
            put("frozen", "0");
        }});
        assertThat(store.init("credit:A", 9999).getOccupied()).isEqualTo(200);
    }

    /** 占用：三态翻译——1 成功重读 / 0 条件不中 empty / -1 键不存在 empty（引擎定责） */
    @Test
    void occupyTranslatesScriptStates() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any())).thenReturn(1L);
        when(hashOps.entries("starsite:quota:credit:A")).thenReturn(new java.util.HashMap<String, String>() {{
            put("total", "1000");
            put("occupied", "300");
            put("frozen", "0");
        }});
        assertThat(store.occupy("credit:A", 300).get().getOccupied()).isEqualTo(300);

        when(redis.execute(any(DefaultRedisScript.class), anyList(), any())).thenReturn(0L);
        assertThat(store.occupy("credit:A", 999)).isEmpty();

        when(redis.execute(any(DefaultRedisScript.class), anyList(), any())).thenReturn(-1L);
        assertThat(store.occupy("credit:A", 1)).isEmpty();
    }

    /** 脚本内容契约：occupy 含不变式 / confirm 含双计数换位 / adjust 含降额保护 */
    @Test
    void scriptContracts() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any())).thenReturn(0L);
        store.occupy("k", 1);
        store.confirm("k", 1);
        store.adjustTotal("k", 1);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<DefaultRedisScript> script = ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redis, org.mockito.Mockito.times(3)).execute(script.capture(), anyList(), any());
        assertThat(script.getAllValues().get(0).getScriptAsString())
                .contains("occ + frz + tonumber(ARGV[1]) <= tonumber(t)");
        assertThat(script.getAllValues().get(1).getScriptAsString())
                .contains("'frozen', tonumber(frz) - tonumber(ARGV[1])")
                .contains("'occupied', occ + tonumber(ARGV[1])");
        assertThat(script.getAllValues().get(2).getScriptAsString())
                .contains("tonumber(t) + tonumber(ARGV[1]) >= occ + frz");
    }

    /** find：hash 三字段解析（缺省零计数）；空 hash → empty */
    @Test
    void findParsesHash() {
        Map<String, String> entries = new HashMap<>();
        entries.put("total", "500");
        when(hashOps.entries("starsite:quota:credit:A")).thenReturn(entries);
        assertThat(store.find("credit:A").get())
                .extracting("total", "occupied", "frozen")
                .containsExactly(500L, 0L, 0L); // occupied/frozen 缺省 0

        when(hashOps.entries("starsite:quota:absent")).thenReturn(new java.util.HashMap<String, String>() {{  }});
        assertThat(store.find("absent")).isEmpty();
    }

    /** 观测降级：countAccounts 保持 -1（无全库计数） */
    @Test
    void observationDegraded() {
        assertThat(store.countAccounts()).isEqualTo(-1);
    }

    /** 键前缀缺省（null → starsite:quota:） */
    @Test
    void keyPrefixDefault() {
        RedisQuotaStore bare = new RedisQuotaStore(redis, null);
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any())).thenReturn(-1L);
        bare.occupy("k", 1);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<java.util.List<String>> keys = ArgumentCaptor.forClass(java.util.List.class);
        verify(redis).execute(any(DefaultRedisScript.class), keys.capture(), any());
        assertThat(keys.getValue()).containsExactly("starsite:quota:k");
    }
    /**
     * JDK8 版 Map.of 替身（测试用——null 安全）。
     *
     * @param kv 键值对序列
     * @return 可变映射
     */
    private static java.util.Map<String, Object> mapOf(Object... kv) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}
