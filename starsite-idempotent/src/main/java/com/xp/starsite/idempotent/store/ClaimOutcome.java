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

/**
 * 认领结局（{@link IdempotentStore#tryClaim} 返回值）。
 *
 * @param claimed true 认领成功（本调用方成为执行者）
 * @param record  认领成功时的新 PROCESSING 行；duplicate 时为 null
 * @author muyi
 * @date 2026/11/12
 */
public record ClaimOutcome(boolean claimed, IdempotentRecord record) {

    /**
     * 构造认领成功结局。
     *
     * @param record 新 PROCESSING 行
     * @return 结局实例
     */
    public static ClaimOutcome claimed(IdempotentRecord record) {
        return new ClaimOutcome(true, record);
    }

    /**
     * 构造键已存在结局（状态不判定——交给调用方读行决定回放/等待/回收）。
     *
     * @return 结局实例
     */
    public static ClaimOutcome duplicate() {
        return new ClaimOutcome(false, null);
    }
}
