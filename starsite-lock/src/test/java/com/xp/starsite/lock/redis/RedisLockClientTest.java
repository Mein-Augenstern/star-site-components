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
package com.xp.starsite.lock.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xp.starsite.lock.model.LockCoreConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis 锁实现单测（Mockito 桩模板）：hash 计数 + Lua 脚本的存储端重入语义。
 * 脚本三态：争锁 1/0；释放 1（归零真放）/0（递减仍持）/-1（非持有者）。
 *
 * @author muyi
 * @date 2026/10/25
 */
class RedisLockClientTest {

    /** Redis 模板桩 */
    private StringRedisTemplate redis;

    /** 被测客户端（owner 固定，TTL 30s） */
    private RedisLockClient client;

    /**
     * 桩装配：全部交互走 execute(script, keys, owner, ttl) 双参形态。
     */
    @BeforeEach
    void setup() {
        redis = mock(StringRedisTemplate.class);
        LockCoreConfig config = new LockCoreConfig();
        config.setOwnerId("inst-a");
        config.setTtlMs(30_000);
        config.setRedisKeyPrefix("starsite:lock:");
        client = new RedisLockClient(redis, config);
    }

    /** 首次争锁：争锁脚本返回 1 */
    @Test
    void 首次争锁_脚本命中() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any())).thenReturn(1L);

        assertThat(client.tryLock("job")).isTrue();

        ArgumentCaptor<DefaultRedisScript<?>> script = ArgumentCaptor.forClass(DefaultRedisScript.class);
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(script.capture(), keys.capture(), any(), any());
        assertThat(script.getValue().getScriptAsString()).contains("HSET");
        assertThat(keys.getValue()).containsExactly("starsite:lock:job");
    }

    /** 他方持锁：争锁脚本返回 0 */
    @Test
    void 他方持锁_脚本未命中() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any())).thenReturn(0L);

        assertThat(client.tryLock("job")).isFalse();
    }

    /** 重入：同 ownerId 再次争锁——脚本 HEXISTS 命中 +1（存储端计数，无本地状态） */
    @Test
    void 自己持锁_脚本重入加层() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any())).thenReturn(1L);

        assertThat(client.tryLock("job")).isTrue();
        assertThat(client.tryLock("job")).isTrue(); // 重入：脚本 HINCRBY，计数在 Redis
    }

    /** 释放归零：脚本返回 1（DEL 分支） */
    @Test
    void 释放归零_脚本DEL() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any())).thenReturn(1L);

        client.unlock("job");

        ArgumentCaptor<DefaultRedisScript<?>> script = ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redis).execute(script.capture(), anyList(), any(), any());
        assertThat(script.getValue().getScriptAsString()).contains("DEL");
    }

    /** 内层释放：脚本返回 0（HINCRBY -1 后仍 >0，key 保留给外层）——不 DEL */
    @Test
    void 内层释放_脚本递减不DEL() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any())).thenReturn(0L);

        client.unlock("job"); // 语义：外层仍持锁

        ArgumentCaptor<DefaultRedisScript<?>> script = ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redis).execute(script.capture(), anyList(), any(), any());
        assertThat(script.getValue().getScriptAsString()).contains("HINCRBY");
    }

    /** 非持有者释放：脚本返回 -1，无副作用不抛 */
    @Test
    void 非持有者释放_脚本负一无副作用() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any())).thenReturn(-1L);

        assertThatCode(() -> client.unlock("job")).doesNotThrowAnyException();
    }

    /** 释放异常：吞掉不抛（锁等 TTL 自动过期，不能拖垮调用方） */
    @Test
    void 释放异常_吞掉不抛() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenThrow(new IllegalStateException("redis down"));

        assertThatCode(() -> client.unlock("job")).doesNotThrowAnyException();
    }

    /** 等待获取（默认方法）：前几轮脚本 0（他方持有），轮询重试后 1（获得） */
    @Test
    void 等待获取_轮询重试后命中() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenReturn(0L, 0L, 1L);

        assertThat(client.tryLock("job", 2_000)).isTrue();
    }
}
