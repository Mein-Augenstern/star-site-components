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
 * 账面状态不一致的防御性拒绝：释放/解冻/转销量大于当前计数，或降额后不足以覆盖
 * 占用+冻结——这不是业务常态（额度不足才是），而是<b>业务侧记账错乱的信号</b>
 * （重复释放/漏记占用），fail-fast 暴露而非静默扣成负数。
 *
 * @author muyi
 * @date 2026/11/14
 */
public class QuotaStateException extends RuntimeException {

    /** 配额键 */
    private final String quotaKey;

    /**
     * 构造。
     *
     * @param message 具体的状态冲突说明（含账面数值）
     * @param quotaKey 配额键
     */
    public QuotaStateException(String message, String quotaKey) {
        super(message);
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
