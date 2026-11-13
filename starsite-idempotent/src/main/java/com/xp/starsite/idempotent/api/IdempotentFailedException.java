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
 * 等待期间他方执行失败：本请求在飞等待中目睹首次执行 FAILED 结局
 * （{@code IdempotentResult.Kind#FAILED} 的注解路径形态），携带他方失败摘要。
 *
 * <p>不自动重跑是刻意语义——失败动作的并发重试风暴比单次失败更危险；
 * 客户端<b>显式</b>重试（新请求）才会走回收重执行路径。
 *
 * @author muyi
 * @date 2026/11/12
 */
public class IdempotentFailedException extends RuntimeException {

    /**
     * 构造他方失败异常。
     *
     * @param message 他方执行的失败摘要（类型+消息，截断 500 同 error 列口径）
     */
    public IdempotentFailedException(String message) {
        super(message);
    }
}
