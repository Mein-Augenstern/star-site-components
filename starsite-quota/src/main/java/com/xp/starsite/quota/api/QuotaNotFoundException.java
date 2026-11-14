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
 * 配额账户不存在：操作前需 {@link QuotaClient#init(String, long)} 建户
 * （fail-fast 而非静默建户——建户总量是业务决策，组件不猜）。
 *
 * @author muyi
 * @date 2026/11/14
 */
public class QuotaNotFoundException extends RuntimeException {

    /** 缺失的配额键 */
    private final String quotaKey;

    /**
     * 构造。
     *
     * @param quotaKey 配额键
     */
    public QuotaNotFoundException(String quotaKey) {
        super("配额账户不存在（先 init 建户）: " + quotaKey);
        this.quotaKey = quotaKey;
    }

    /**
     * 获取配额键。
     *
     * @return 配额键
     */
    public String getQuotaKey() {
        return quotaKey;
    }
}
