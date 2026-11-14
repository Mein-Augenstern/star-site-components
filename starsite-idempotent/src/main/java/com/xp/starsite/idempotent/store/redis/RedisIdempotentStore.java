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

import com.xp.starsite.idempotent.api.IdempotentRecord;
import com.xp.starsite.idempotent.api.IdempotentStatus;
import com.xp.starsite.idempotent.clock.TimeSource;
import com.xp.starsite.idempotent.store.ClaimOutcome;
import com.xp.starsite.idempotent.store.IdempotentStore;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis 幂等存储（缓存形态——已有 Redis 无 MySQL 的场景，与 lock/sequence 的 redis
 * 形态同家族定位）：每键一个 hash（status/result/error/expire_at/host/gmt_modified 六字段），
 * 四操作各一段 Lua 原子完成（脚本外置 {@code resources/sql/idempotent-*.lua}，本类零脚本字符串）。
 *
 * <p><b>键记忆窗口 = Redis TTL</b>：认领/回收时 PEXPIRE；TTL 归零键消失即"键遗忘"——
 * 与表形态 expire_at 过期完全同语义，且过期清理由 Redis 原生承担（purgeExpired 缺省 0）。
 * 观测降级：无全库计数（SCAN 代价与健康端点不匹配）——countByStatus 保持缺省 -1，
 * 健康端点省略微报（与 sequence redis 形态"审计信息不如 MySQL 行可查"同取舍）。
 *
 * <p>时间值用<b>定长 ISO 串</b>（uuuu-MM-dd'T'HH:mm:ss.SSS）：Lua 字节序比较即时间序，
 * 免 epoch 换算与时区问题（容器 UTC 陷阱家族实证两次）。多实例经同一 ISO 口径比较，
 * 跨实例一致。
 *
 * <p>正确性与表形态逐条对齐：认领互斥=EXISTS+HSET 单脚本原子（SET NX 语义）；回收=
 * 三合一条件单脚本；终态=双条件守卫（PROCESSING + 本代 expire_at 代际令牌）。
 *
 * @author muyi
 * @date 2026/11/13
 */
public class RedisIdempotentStore implements IdempotentStore {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotentStore.class);

    /**
     * 认领脚本（原子）：key 不存在建 PROCESSING hash + PEXPIRE 窗口；存在返回 0。
     * <p>配置外置：{@code sql/idempotent-claim.lua}。
     */
    private static final String CLAIM_LUA = loadScript("sql/idempotent-claim.lua");

    /**
     * 条件回收脚本（原子，三合一）：FAILED / 过期 / PROCESSING 停滞 → 重置换代；
     * key 不存在 -1；不可回收 0。配置外置：{@code sql/idempotent-reclaim.lua}。
     */
    private static final String RECLAIM_LUA = loadScript("sql/idempotent-reclaim.lua");

    /**
     * 落成功终态脚本（双条件守卫）：配置外置 {@code sql/idempotent-complete.lua}。
     */
    private static final String COMPLETE_LUA = loadScript("sql/idempotent-complete.lua");

    /**
     * 落失败终态脚本（双条件守卫）：配置外置 {@code sql/idempotent-fail.lua}。
     */
    private static final String FAIL_LUA = loadScript("sql/idempotent-fail.lua");

    /** 定长 ISO 时间格式（毫秒三位固定——字节序比较即时间序；年四位固定覆盖现实区间） */
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS", Locale.ENGLISH);

    /** hash 字段名：状态 */
    private static final String F_STATUS = "status";

    /** hash 字段名：回放载荷 */
    private static final String F_RESULT = "result";

    /** hash 字段名：失败摘要 */
    private static final String F_ERROR = "error";

    /** hash 字段名：键记忆窗口边界（代际令牌） */
    private static final String F_EXPIRE = "expire_at";

    /** hash 字段名：认领实例 */
    private static final String F_HOST = "host";

    /** hash 字段名：活跃信号（停滞判崩溃遗留） */
    private static final String F_MODIFIED = "gmt_modified";

    /** Redis 字符串模板 */
    private final StringRedisTemplate redis;

    /** 键前缀（默认 starsite:idem:——与 lock 的 starsite:lock: 同命名法） */
    private final String keyPrefix;

    /** 时间源（TTL 时长计算与 gmt_modified 取值） */
    private final TimeSource time;

    /**
     * 便捷构造（系统时钟）。
     *
     * @param redis     Redis 字符串模板
     * @param keyPrefix 键前缀
     */
    public RedisIdempotentStore(StringRedisTemplate redis, String keyPrefix) {
        this(redis, keyPrefix, TimeSource.system());
    }

    /**
     * 全参构造（测试注入假时钟推窗口）。
     *
     * @param redis     Redis 字符串模板
     * @param keyPrefix 键前缀
     * @param time      时间源
     */
    public RedisIdempotentStore(StringRedisTemplate redis, String keyPrefix, TimeSource time) {
        this.redis = redis;
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "starsite:idem:" : keyPrefix;
        this.time = time;
    }

    /** {@inheritDoc} EXISTS+HSET 单脚本原子（SET NX 语义——与表形态 uk INSERT 认领等价） */
    @Override
    public ClaimOutcome tryClaim(String scope, String idemKey, LocalDateTime expireAt, String host) {
        String key = key(scope, idemKey);
        Long r = redis.execute(new DefaultRedisScript<>(CLAIM_LUA, Long.class), List.of(key),
                ttlMillis(expireAt), iso(expireAt), host, iso(time.now()));
        if (r != null && r == 1) {
            // id=0：redis 形态无 DB 主键（引擎终态操作走 scope+key 自然键，id 不参与）
            return ClaimOutcome.claimed(new IdempotentRecord(0L, scope == null ? "" : scope,
                    idemKey, IdempotentStatus.PROCESSING, null, null, expireAt, null, host,
                    null, null));
        }
        return ClaimOutcome.duplicate();
    }

    /** {@inheritDoc} 回收成功后重读 hash（拿新代 expire_at/gmt_modified——判定与留痕同表形态） */
    @Override
    public Optional<IdempotentRecord> find(String scope, String idemKey) {
        Map<String, String> entries = redis.<String, String>opsForHash().entries(key(scope, idemKey));
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new IdempotentRecord(0L, scope == null ? "" : scope, idemKey,
                IdempotentStatus.fromCode(field(entries, F_STATUS)),
                field(entries, F_RESULT), field(entries, F_ERROR),
                parse(field(entries, F_EXPIRE)), null,
                field(entries, F_HOST), null, parse(field(entries, F_MODIFIED))));
    }

    /** {@inheritDoc} 三合一条件单脚本原子；-1（键遗忘）与 0（不可回收）统一 empty */
    @Override
    public Optional<IdempotentRecord> tryReclaim(String scope, String idemKey,
                                                 LocalDateTime expireAt, LocalDateTime now,
                                                 LocalDateTime staleBefore, String host) {
        Long r = redis.execute(new DefaultRedisScript<>(RECLAIM_LUA, Long.class),
                List.of(key(scope, idemKey)), ttlMillis(expireAt), iso(expireAt), host,
                iso(now), iso(staleBefore));
        return r != null && r == 1 ? find(scope, idemKey) : Optional.empty();
    }

    /** {@inheritDoc} 双条件守卫（PROCESSING + 本代 expire_at）——代际令牌与表形态同语义 */
    @Override
    public boolean complete(String scope, String idemKey, LocalDateTime claimExpireAt, String result) {
        Long r = redis.execute(new DefaultRedisScript<>(COMPLETE_LUA, Long.class),
                List.of(key(scope, idemKey)), iso(claimExpireAt), result == null ? "" : result,
                result == null ? "0" : "1", iso(time.now()));
        return r != null && r == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean fail(String scope, String idemKey, LocalDateTime claimExpireAt, String error) {
        Long r = redis.execute(new DefaultRedisScript<>(FAIL_LUA, Long.class),
                List.of(key(scope, idemKey)), iso(claimExpireAt), error == null ? "" : error,
                error == null ? "0" : "1", iso(time.now()));
        return r != null && r == 1;
    }

    /** {@inheritDoc} 观测降级：保持缺省 -1（无全库计数）——健康端点省略微报 */
    @Override
    public long countByStatus(IdempotentStatus status) {
        return -1;
    }

    /** {@inheritDoc} 观测降级：保持缺省 0——Redis TTL 原生过期，无需清理看护 */
    @Override
    public int purgeExpired(int batchSize, LocalDateTime now) {
        return 0;
    }

    /**
     * 键布局：前缀 + scope + 不可输入分隔符 + idemKey（与 memory 形态同法——
     * 防跨字段拼接歧义；分隔符 \u0001 不出现在业务输入）。
     *
     * @param scope   键作用域（null 归一空串）
     * @param idemKey 幂等键
     * @return Redis 键
     */
    private String key(String scope, String idemKey) {
        return keyPrefix + (scope == null ? "" : scope) + '\u0001' + idemKey;
    }

    /** TTL 毫秒（窗口边界 − 当前时刻；引擎恒以 now+ttl 认领，值为正） */
    private long ttlMillis(LocalDateTime expireAt) {
        return Math.max(1, Duration.between(time.now(), expireAt).toMillis());
    }

    /**
     * 从 classpath 加载 Lua 脚本（静态一次；缺文件快速失败——装配期暴露）。
     *
     * @param path classpath 相对路径
     * @return 脚本原文（去首尾空白）
     */
    private static String loadScript(String path) {
        try (java.io.InputStream in = RedisIdempotentStore.class.getClassLoader()
                .getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Redis 幂等脚本缺失: " + path);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Redis 幂等脚本加载失败: " + path, e);
        }
    }

    /** LocalDateTime → 定长 ISO 串（字节序=时间序） */
    private static String iso(LocalDateTime t) {
        return t == null ? "" : ISO.format(t);
    }

    /** 定长 ISO 串 → LocalDateTime（空串回 null——健壮性防御） */
    private static LocalDateTime parse(String s) {
        return s == null || s.isBlank() ? null : LocalDateTime.parse(s, ISO);
    }

    /** hash 条目取字段值（缺失为 null） */
    private static String field(Map<String, String> entries, String name) {
        return entries.get(name);
    }
}
