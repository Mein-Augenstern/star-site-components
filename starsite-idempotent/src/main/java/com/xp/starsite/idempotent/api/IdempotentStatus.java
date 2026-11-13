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
package com.xp.starsite.idempotent.api;

/**
 * 幂等记录状态机（idempotent_record.status）。
 *
 * <pre>
 * (缺行) ──uk INSERT 原子认领──► PROCESSING ──执行成功──► DONE ──expire_at 过期──┐
 *                                  │                                            │
 *                                  ├──执行抛异常──► FAILED（键释放，可回收重执行）◄──┤
 *                                  │                                            │
 *                                  └──认领者崩溃──► PROCESSING 停滞超阈值 ────────┤
 *                                                                             ▼
 *                                              下一个同键请求条件回收（tryReclaim）再执行
 * </pre>
 *
 * <p>与 DONE 的边界：DONE 行在 expire_at（键记忆窗口，默认 24h——Stripe 同款）内可回放；
 * 过期后等同"键遗忘"，新请求重新执行。FAILED 行立即可回收——失败释放键是幂等键与缓存
 * 的关键语义差异（Stripe 5xx 同款）。
 *
 * @author muyi
 * @date 2026/11/12
 */
public enum IdempotentStatus {

    /** 执行中（认领时写入；gmt_modified 为活跃信号——停滞超阈值 = 崩溃遗留） */
    PROCESSING,

    /** 执行成功（result 载荷已存，expire_at 内重复请求直接回放） */
    DONE,

    /** 执行失败（error 摘要留痕；键已释放——新请求回收重执行） */
    FAILED;

    /**
     * 是否终态（不再自动流转；过期/回收是<b>新请求驱动</b>的条件转移，非定时器驱动）。
     *
     * @return true 表示 DONE / FAILED
     */
    public boolean isTerminal() {
        return this != PROCESSING;
    }

    /**
     * 是否可回放（DONE 且未过 expire_at 时引擎直接返回存储载荷）。
     *
     * @return true 表示 DONE
     */
    public boolean isReplayable() {
        return this == DONE;
    }

    /**
     * 解析存储的 status code（列值与枚举名一致）。
     *
     * @param code 列值
     * @return 枚举
     * @throws IllegalArgumentException 未知列值（结构漂移提前暴露）
     */
    public static IdempotentStatus fromCode(String code) {
        for (IdempotentStatus s : values()) {
            if (s.name().equals(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知幂等状态: " + code);
    }
}
