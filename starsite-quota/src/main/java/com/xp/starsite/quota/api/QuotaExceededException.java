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
 * 可用量不足（occupy/freeze 被不变式拒绝）：携带当前可用量与请求量——
 * 业务侧可直接展示"还可支用/购买多少"（信贷额度不足提示、库存不足提示同款）。
 *
 * <p>这是业务常态而非系统错误——拒绝本身是不变式在正确工作。
 *
 * @author muyi
 * @date 2026/11/14
 */
public class QuotaExceededException extends RuntimeException {

    /** 配额键 */
    private final String quotaKey;

    /** 请求量（被拒绝的） */
    private final long requested;

    /** 拒绝时的可用量 */
    private final long available;

    /**
     * 构造。
     *
     * @param quotaKey  配额键
     * @param requested 请求量
     * @param available 拒绝时的可用量
     */
    public QuotaExceededException(String quotaKey, long requested, long available) {
        super("配额可用量不足: key=" + quotaKey + ", 可用=" + available + ", 请求=" + requested);
        this.quotaKey = quotaKey;
        this.requested = requested;
        this.available = available;
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
     * 获取请求量。
     *
     * @return 被拒绝的请求量
     */
    public long getRequested() {
        return requested;
    }

    /**
     * 获取拒绝时的可用量。
     *
     * @return 可用量
     */
    public long getAvailable() {
        return available;
    }
}
