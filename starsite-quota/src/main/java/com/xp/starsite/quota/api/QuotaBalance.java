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
package com.xp.starsite.quota.api;

/**
 * 配额账户余额快照（一次原子操作后的账面视图；不可变值对象）。
 *
 * <p>双构造器形态（与 schedule RunLog 同形）：{@code long} 形态给业务侧与引擎；
 * {@code Long} 包装形态给 MyBatis 构造器映射（反射按包装类型找构造器，实参自动装箱）。
 *
 * @author muyi
 * @date 2026/11/14
 */
public final class QuotaBalance {

    /** 配额键 */
    private final String quotaKey;

    /** 总量 */
    private final long total;

    /** 已占用 */
    private final long occupied;

    /** 冻结中 */
    private final long frozen;

    /**
     * 业务形态构造（long 原始类型）。
     *
     * @param quotaKey 配额键
     * @param total    总量
     * @param occupied 已占用
     * @param frozen   冻结中
     */
    public QuotaBalance(String quotaKey, long total, long occupied, long frozen) {
        this.quotaKey = quotaKey;
        this.total = total;
        this.occupied = occupied;
        this.frozen = frozen;
    }

    /**
     * MyBatis 映射形态构造（包装类型——反射按此找构造器，null 防 0）。
     *
     * @param quotaKey 配额键（可 null——反射映射缺列时视为空串）
     * @param total    总量（可 null——视为 0）
     * @param occupied 已占用（可 null——视为 0）
     * @param frozen   冻结中（可 null——视为 0）
     */
    public QuotaBalance(String quotaKey, Long total, Long occupied, Long frozen) {
        this(quotaKey, total == null ? 0L : total,
                occupied == null ? 0L : occupied, frozen == null ? 0L : frozen);
    }

    /**
     * 获取配额键。
     *
     * @return 配额键
     */
    public String getQuotaKey() {
        return quotaKey;
    }

    /**
     * 获取总量。
     *
     * @return 总量
     */
    public long getTotal() {
        return total;
    }

    /**
     * 获取已占用。
     *
     * @return 已占用量
     */
    public long getOccupied() {
        return occupied;
    }

    /**
     * 获取冻结中。
     *
     * @return 冻结量
     */
    public long getFrozen() {
        return frozen;
    }

    /**
     * 可用量 = 总量 − 占用 − 冻结（不变式保证非负）。
     *
     * @return 当前可用量
     */
    public long getAvailable() {
        return total - occupied - frozen;
    }
}
