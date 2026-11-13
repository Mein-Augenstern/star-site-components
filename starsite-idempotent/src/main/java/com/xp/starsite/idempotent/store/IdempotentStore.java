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
package com.xp.starsite.idempotent.store;

import com.xp.starsite.idempotent.api.IdempotentRecord;
import com.xp.starsite.idempotent.api.IdempotentStatus;
import java.time.LocalDateTime;

/**
 * 幂等存储 SPI（四内置实现同语义等价——jdbc/mybatis 表形态 + redis 缓存形态 +
 * memory 单机形态，行为差异由等价测试互证，reviewer 第 5 尺）。
 *
 * <p><b>第三方存储扩展点</b>：实现本接口并注册为 Spring Bean（starter 全量让位）
 * 即可接入任意自建存储（如 TBase/OceanBase 等 MySQL 兼容库可直接用 jdbc 形态，
 * 非 MySQL 方言实现 {@link IdempotentSql} 注册 Bean 即换方言；完全异构存储实现本接口）。
 * 契约要点：tryClaim/tryReclaim/complete/fail 四操作必须<b>存储端原子</b>——
 * 这是多实例恰一执行的全部正确性来源（docs/idempotent.md §扩展）。
 *
 * <p>正确性全部落在存储端原子操作上（分布式状态存储端铁律 D4）：
 * <ul>
 *   <li>认领互斥：uk(scope, idem_key) 的 INSERT 原子性（表形态）/ SET NX 原子性
 *       （redis）/ compute 原子性（memory）</li>
 *   <li>回收互斥：单条条件 UPDATE / Lua 条件重置（FAILED / 过期 / PROCESSING 停滞三合一条件）</li>
 *   <li>终态守卫：complete/fail 双条件（PROCESSING + 本代 expire_at）——被回收的行
 *       不接受旧认领者的终态写入（代际令牌）</li>
 * </ul>
 *
 * @author muyi
 * @date 2026/11/12
 */
public interface IdempotentStore {

    /**
     * 认领键（原子）：键空闲则插入 PROCESSING 行并返回认领成功；键已存在返回 duplicate
     * （不区分状态——去读行决定回放/等待/回收，减少一次竞态窗口内的双重判定）。
     *
     * @param scope    键作用域
     * @param idemKey  幂等键
     * @param expireAt 键记忆窗口边界（now + key-ttl，写入行）
     * @param host     认领实例标识（hostname:pid，观测留痕）
     * @return 认领结局（claimed 时携带新行）
     */
    ClaimOutcome tryClaim(String scope, String idemKey, LocalDateTime expireAt, String host);

    /**
     * 读键详情（uk 点查全列含 result/error 详情列）。
     *
     * @param scope   键作用域
     * @param idemKey 幂等键
     * @return 记录；键不存在为 empty（可能被 retention 清理——调用方按"键空闲"重试认领）
     */
    java.util.Optional<IdempotentRecord> find(String scope, String idemKey);

    /**
     * 回收再执行（原子，条件三合一）：行处于 FAILED、或 expire_at 已过、或
     * PROCESSING 且 gmt_modified 早于停滞阈值——满足其一则重置为 PROCESSING
     * （新 expire_at/host）并返回回收后的行；否则 empty（他方在飞或已完成，
     * 调用方回到读行路径）。
     *
     * @param scope       键作用域
     * @param idemKey     幂等键
     * @param expireAt    新键记忆窗口边界
     * @param now         当前时刻（过期判定基准——参数绑定与调用方时钟同源可测）
     * @param staleBefore PROCESSING 停滞阈值时刻（早于此即崩溃遗留）
     * @param host        回收实例标识
     * @return 回收后的行；不满足回收条件为 empty
     */
    java.util.Optional<IdempotentRecord> tryReclaim(String scope, String idemKey,
                                                    LocalDateTime expireAt, LocalDateTime now,
                                                    LocalDateTime staleBefore, String host);

    /**
     * 落成功终态（双条件守卫：status=PROCESSING <b>且</b> expire_at 等于本代认领写入值——
     * 认领代际令牌。行被回收是原地重置，仅按 status 守卫无法区分新旧认领者：迟到者会
     * 写脏新认领者的行。expire_at 每代认领必换新，是免费的代际标识）。
     * <p>以 (scope, idemKey) 自然键寻址——表形态命中 uk，Redis 形态即存储键，
     * 两种形态同一 SPI（第三方存储扩展点）。
     *
     * @param scope        键作用域
     * @param idemKey      幂等键
     * @param claimExpireAt 本代认领写入的 expire_at（引擎从认领/回收快照透传）
     * @param result       回放载荷
     * @return true 写入成功；false 行已非本代（被回收——结果弃写，调用方 WARN 留痕）
     */
    boolean complete(String scope, String idemKey, LocalDateTime claimExpireAt, String result);

    /**
     * 落失败终态（守卫同 {@link #complete}——仅本代 PROCESSING 行接受）。
     *
     * @param scope        键作用域
     * @param idemKey      幂等键
     * @param claimExpireAt 本代认领写入的 expire_at
     * @param error        失败摘要（类型+消息截断 500）
     * @return true 写入成功；false 行已被回收
     */
    boolean fail(String scope, String idemKey, LocalDateTime claimExpireAt, String error);

    /**
     * 按状态计数（健康端点用——低频，不承诺索引命中）。
     * <p><b>观测降级缺省</b>：不支持全库计数的形态（Redis——SCAN 代价与健康端点不匹配）
     * 保持缺省返回 -1，健康端点对负值省略微报——SPI 扩展方按自家存储能力选择覆写。
     *
     * @param status 状态
     * @return 行数；不支持计数为 -1
     */
    default long countByStatus(IdempotentStatus status) {
        return -1;
    }

    /**
     * 清理过期行（语义已死，纯表卫生）。<b>观测降级缺省</b>：自带原生过期的形态
     * （Redis TTL）无需清理，保持缺省返回 0；表形态覆写（两步法批量：圈主键 →
     * 主键集合删除，全程参数绑定，分片红线合规）。
     *
     * @param batchSize 单轮批量上限
     * @param now       当前时刻（过期判定基准）
     * @return 本轮清理行数
     */
    default int purgeExpired(int batchSize, LocalDateTime now) {
        return 0;
    }
}
