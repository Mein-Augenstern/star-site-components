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

import com.xp.starsite.quota.api.QuotaBalance;
import com.xp.starsite.quota.store.QuotaStore;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis 配额存储（缓存形态——已有 Redis 无 MySQL 的场景，与 lock/idempotent 的
 * redis 形态同家族定位）：每账户一个 hash（total/occupied/frozen 三字段），
 * 六操作各一段 Lua 原子完成（脚本外置 {@code resources/sql/quota-*.lua}，
 * 本类零脚本字符串）。
 *
 * <p>与表形态逐条语义对齐：不变式判定与计数更新同在单段脚本内原子执行——
 * 多实例并发下超量必拒。账户无 TTL（配额账户是持久状态，非瞬态）；
 * 观测降级：无全库计数（SCAN 代价与健康端点不匹配），保持缺省 -1。
 *
 * <p>数值经字符串往返（HGET/HSET 均字符串态）——Lua 内 tonumber 转换，
 * long 范围内精度无损。
 *
 * @author muyi
 * @date 2026/11/14
 */
public class RedisQuotaStore implements QuotaStore {

    private static final Logger log = LoggerFactory.getLogger(RedisQuotaStore.class);

    /** 建户脚本（原子）：不存在建三计数器；存在不动返回 0（幂等保留现量） */
    private static final String INIT_LUA = loadScript("sql/quota-init.lua");

    /** 占用脚本（不变式条件内） */
    private static final String OCCUPY_LUA = loadScript("sql/quota-occupy.lua");

    /** 释放占用脚本 */
    private static final String RELEASE_LUA = loadScript("sql/quota-release.lua");

    /** 冻结脚本（与占用共享总量池） */
    private static final String FREEZE_LUA = loadScript("sql/quota-freeze.lua");

    /** 解冻脚本 */
    private static final String UNFREEZE_LUA = loadScript("sql/quota-unfreeze.lua");

    /** 冻结转占用脚本（单脚本双计数换位） */
    private static final String CONFIRM_LUA = loadScript("sql/quota-confirm.lua");

    /** 调整总量脚本（降额保护） */
    private static final String ADJUST_LUA = loadScript("sql/quota-adjust.lua");

    /** hash 字段：总量 */
    private static final String F_TOTAL = "total";

    /** hash 字段：已占用 */
    private static final String F_OCCUPIED = "occupied";

    /** hash 字段：冻结中 */
    private static final String F_FROZEN = "frozen";

    /** Redis 字符串模板 */
    private final StringRedisTemplate redis;

    /** 键前缀（默认 starsite:quota:——与 lock/idempotent 同命名法） */
    private final String keyPrefix;

    /**
     * 便捷构造。
     *
     * @param redis     Redis 字符串模板
     * @param keyPrefix 键前缀
     */
    public RedisQuotaStore(StringRedisTemplate redis, String keyPrefix) {
        this.redis = redis;
        this.keyPrefix = keyPrefix == null || keyPrefix.trim().isEmpty() ? "starsite:quota:" : keyPrefix;
    }

    /** {@inheritDoc} 脚本三态：1=建户 / 0=已存在（保留现量）——均返回账面 */
    @Override
    public QuotaBalance init(String quotaKey, long total) {
        Long r = redis.execute(new DefaultRedisScript<>(INIT_LUA, Long.class),
                java.util.Arrays.asList(key(quotaKey)), String.valueOf(total));
        if (r != null && r == 1) {
            return new QuotaBalance(quotaKey, total, 0, 0);
        }
        return find(quotaKey).get(); // 0=已存在 → 现量；理论无其他态
    }

    /** {@inheritDoc} */
    @Override
    public Optional<QuotaBalance> find(String quotaKey) {
        Map<String, String> entries = redis.<String, String>opsForHash().entries(key(quotaKey));
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new QuotaBalance(quotaKey,
                Long.parseLong(entries.get(F_TOTAL)),
                Long.parseLong(entries.getOrDefault(F_OCCUPIED, "0")),
                Long.parseLong(entries.getOrDefault(F_FROZEN, "0"))));
    }

    /** {@inheritDoc} 脚本三态：1=成功（重读账面）/ 0=条件不中 / -1=键不存在——后两态统一 empty（引擎定责） */
    @Override
    public Optional<QuotaBalance> occupy(String quotaKey, long amount) {
        return mutate(OCCUPY_LUA, quotaKey, amount);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<QuotaBalance> release(String quotaKey, long amount) {
        return mutate(RELEASE_LUA, quotaKey, amount);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<QuotaBalance> freeze(String quotaKey, long amount) {
        return mutate(FREEZE_LUA, quotaKey, amount);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<QuotaBalance> unfreeze(String quotaKey, long amount) {
        return mutate(UNFREEZE_LUA, quotaKey, amount);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<QuotaBalance> confirm(String quotaKey, long amount) {
        return mutate(CONFIRM_LUA, quotaKey, amount);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<QuotaBalance> adjustTotal(String quotaKey, long delta) {
        return mutate(ADJUST_LUA, quotaKey, delta);
    }

    /** {@inheritDoc} 观测降级：保持缺省 -1（无全库计数） */
    @Override
    public long countAccounts() {
        return -1;
    }

    /**
     * 通用原子变更：执行脚本，1 → 重读账面；0 / -1 / null → empty。
     *
     * @param script   Lua 脚本原文
     * @param quotaKey 配额键
     * @param operand  操作量
     * @return 变更后账面；条件不中/键不存在为 empty
     */
    private Optional<QuotaBalance> mutate(String script, String quotaKey, long operand) {
        Long r = redis.execute(new DefaultRedisScript<>(script, Long.class),
                java.util.Arrays.asList(key(quotaKey)), String.valueOf(operand));
        return r != null && r == 1 ? find(quotaKey) : Optional.empty();
    }

    /**
     * 键布局：前缀 + 配额键（配额键本身即业务全键——无需作用域段）。
     *
     * @param quotaKey 配额键
     * @return Redis 键
     */
    private String key(String quotaKey) {
        return keyPrefix + quotaKey;
    }

    /**
     * 从 classpath 加载 Lua 脚本（静态一次；缺文件快速失败——装配期暴露）。
     *
     * @param path classpath 相对路径
     * @return 脚本原文（去首尾空白）
     */
    private static String loadScript(String path) {
        try (java.io.InputStream in = RedisQuotaStore.class.getClassLoader()
                .getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Redis 配额脚本缺失: " + path);
            }
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    buf.write(chunk, 0, n);
                }
                return new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Redis 配额脚本加载失败: " + path, e);
        }
    }
}
