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
 * 在飞冲突：同键执行正在进行且本请求等待窗口耗尽（{@code IdempotentResult.Kind#CONFLICT}
 * 的注解路径形态）。
 *
 * <p>web 侧建议映射 409 Conflict（请求本身无错，稍后重试即回放结果）——
 * starter 不引 spring-web（core/starter 双模块铁律），映射交给消费方：
 * <pre>{@code
 * @ExceptionHandler(IdempotentConflictException.class)
 * ResponseEntity<?> conflict(IdempotentConflictException e) {
 *     return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
 * }
 * }</pre>
 *
 * @author muyi
 * @date 2026/11/12
 */
public class IdempotentConflictException extends RuntimeException {

    /**
     * 构造在飞冲突异常。
     *
     * @param message 冲突说明（含键与等待窗口信息，供日志与响应定位）
     */
    public IdempotentConflictException(String message) {
        super(message);
    }
}
