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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xp.starsite.lock.model.LockCoreConfig;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis 锁实现单测（Mockito 桩 RedisTemplate）：争锁/续期/释放的脚本语义。
 * 核心断言：续期必须是单条 Lua CAS（GET+EXPIRE 两步竞态会误延他方锁并误判持锁）。
 *
 * @author muyi
 * @date 2026/10/25
 */
class RedisLockClientTest {

    /** Redis 模板桩 */
    private StringRedisTemplate redis;

    /** 值操作桩（opsForValue 返回） */
    private ValueOperations<String, String> valueOps;

    /** 被测客户端（owner 固定，TTL 30s） */
    private RedisLockClient client;

    /**
     * 桩装配：template.opsForValue → valueOps；execute 按用例各自打桩。
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        LockCoreConfig config = new LockCoreConfig();
        config.setOwnerId("inst-a");
        config.setTtlMs(30_000);
        config.setRedisKeyPrefix("starsite:lock:");
        client = new RedisLockClient(redis, config);
    }

    /** 首次争锁：SET NX 命中即持锁，不走任何脚本 */
    @Test
    void 首次争锁_SETNX命中_直接持锁() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        assertThat(client.tryLock("job")).isTrue();
        verify(valueOps).setIfAbsent("starsite:lock:job", "inst-a", Duration.ofMillis(30_000));
    }

    /** 他方持锁：SET NX 未命中且续期脚本未命中（已易主/非本人）——争锁失败 */
    @Test
    void 他方持锁_续期脚本未命中_争锁失败() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any())).thenReturn(0L);

        assertThat(client.tryLock("job")).isFalse();
    }

    /** 自己持锁：SET NX 未命中（key 已存在）但续期脚本命中——原子续期成功 */
    @Test
    void 自己持锁_续期脚本命中_续期成功() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any())).thenReturn(1L);

        assertThat(client.tryLock("job")).isTrue();

        // 续期必须是 Lua CAS 脚本（PEXPIRE 分支），且携带 owner 与 TTL 毫秒两参
        ArgumentCaptor<DefaultRedisScript<?>> script = ArgumentCaptor.forClass(DefaultRedisScript.class);
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(script.capture(), keys.capture(), any(), any());
        assertThat(script.getValue().getScriptAsString()).contains("PEXPIRE");
        assertThat(keys.getValue()).containsExactly("starsite:lock:job");
    }

    /** 释放锁：走 Lua CAS 脚本（DEL 分支），携带 owner 单参 */
    @Test
    void 释放锁_走CAS脚本() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any())).thenReturn(1L);

        client.unlock("job");

        ArgumentCaptor<DefaultRedisScript<?>> script = ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redis).execute(script.capture(), anyList(), any());
        assertThat(script.getValue().getScriptAsString()).contains("DEL");
    }

    /** 释放异常：吞掉不抛（锁等 TTL 自动过期，不能拖垮调用方） */
    @Test
    void 释放异常_吞掉不抛() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenThrow(new IllegalStateException("redis down"));

        assertThatCode(() -> client.unlock("job")).doesNotThrowAnyException();
    }

    /**
     * 重入（行为版断言，跟随者视角证明）：嵌套两层后内层退出——跟随者仍抢不到（外层持锁中，
     * 内层退出没有 DEL）；最外层退出后跟随者立即抢到（此刻才真正释放）。
     */
    @Test
    void 重入_内层退出不放锁_最外层退出才放() {
        LockCoreConfig followerCfg = new LockCoreConfig();
        followerCfg.setOwnerId("inst-b");
        RedisLockClient follower = new RedisLockClient(redis, followerCfg);

        // 顺序：外层 SETNX 命中 → 内层 SETNX 落空 → 跟随者 SETNX 落空 → 最外层释放后跟随者命中
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true, false, false, true);
        // 续期脚本（owner+ttl 两参形态）：内层重入命中 1；跟随者试探落空 0（key 仍归 leader）
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenReturn(1L, 0L);
        // 释放脚本（owner 单参形态）：命中 1
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any())).thenReturn(1L);

        assertThat(client.tryLock("job")).isTrue();   // 外层获得（层数 1）
        assertThat(client.tryLock("job")).isTrue();   // 内层重入（层数 2）
        client.unlock("job");                          // 内层退出——不 DEL
        assertThat(follower.tryLock("job")).isFalse(); // 外层仍持锁，跟随者进不来

        client.unlock("job");                          // 最外层退出——真正 CAS 释放
        assertThat(follower.tryLock("job")).isTrue();  // 跟随者立即接管
    }
}
