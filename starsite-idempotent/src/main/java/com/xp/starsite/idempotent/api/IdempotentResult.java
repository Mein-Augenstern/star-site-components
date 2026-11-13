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
 * 幂等执行的结局（门面返回值——编程式消费方看到全部四态；注解路径把
 * CONFLICT/FAILED 翻译为异常，EXECUTED/REPLAYED 正常返回）。
 *
 * <p>四态语义：
 * <ul>
 *   <li>{@link Kind#EXECUTED}：本请求认领成功并执行了业务动作（payload 为动作返回载荷）</li>
 *   <li>{@link Kind#REPLAYED}：窗口内重复请求，业务动作零执行（payload 为首次存储的载荷）</li>
 *   <li>{@link Kind#CONFLICT}：他方在飞且等待窗口耗尽（可稍后重试或映射 409）</li>
 *   <li>{@link Kind#FAILED}：本请求等待期间他方执行失败（返回失败摘要——不自动重跑，
 *       防失败动作的重试风暴；失败后的<b>新</b>请求才会回收重执行）</li>
 * </ul>
 *
 * @author muyi
 * @date 2026/11/12
 */
public final class IdempotentResult {

    /** 结局种类 */
    private final Kind kind;

    /** 回放/执行载荷（CONFLICT/FAILED 为 null） */
    private final String payload;

    /** 失败摘要（仅 FAILED 有值） */
    private final String error;

    /**
     * 全参构造（经静态工厂构造，不直接 new）。
     *
     * @param kind    结局
     * @param payload 载荷
     * @param error   失败摘要
     */
    private IdempotentResult(Kind kind, String payload, String error) {
        this.kind = kind;
        this.payload = payload;
        this.error = error;
    }

    /**
     * 构造"本请求执行"结局。
     *
     * @param payload 动作返回载荷
     * @return 结局实例
     */
    public static IdempotentResult executed(String payload) {
        return new IdempotentResult(Kind.EXECUTED, payload, null);
    }

    /**
     * 构造"回放首次结果"结局。
     *
     * @param payload 存储的首次载荷
     * @return 结局实例
     */
    public static IdempotentResult replayed(String payload) {
        return new IdempotentResult(Kind.REPLAYED, payload, null);
    }

    /**
     * 构造"在飞冲突"结局。
     *
     * @return 结局实例
     */
    public static IdempotentResult conflict() {
        return new IdempotentResult(Kind.CONFLICT, null, null);
    }

    /**
     * 构造"等待期间他方失败"结局。
     *
     * @param error 他方执行的失败摘要
     * @return 结局实例
     */
    public static IdempotentResult failed(String error) {
        return new IdempotentResult(Kind.FAILED, null, error);
    }

    /**
     * 获取结局种类。
     *
     * @return 结局
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * 获取载荷。
     *
     * @return EXECUTED/REPLAYED 为动作/存储载荷；其余为 null
     */
    public String getPayload() {
        return payload;
    }

    /**
     * 获取失败摘要。
     *
     * @return FAILED 为他方失败摘要；其余为 null
     */
    public String getError() {
        return error;
    }

    /**
     * 是否本请求执行了动作。
     *
     * @return true 表示 EXECUTED
     */
    public boolean isExecuted() {
        return kind == Kind.EXECUTED;
    }

    /**
     * 是否回放（业务动作零执行——幂等键的核心价值态）。
     *
     * @return true 表示 REPLAYED
     */
    public boolean isReplayed() {
        return kind == Kind.REPLAYED;
    }

    /** 结局种类 */
    public enum Kind {

        /** 本请求认领成功并执行 */
        EXECUTED,

        /** 窗口内重复请求，回放首次结果 */
        REPLAYED,

        /** 他方在飞且等待窗口耗尽 */
        CONFLICT,

        /** 等待期间他方执行失败（不自动重跑） */
        FAILED
    }
}
