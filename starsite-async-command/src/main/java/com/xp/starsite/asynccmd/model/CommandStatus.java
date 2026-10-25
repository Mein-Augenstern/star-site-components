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
package com.xp.starsite.asynccmd.model;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 命令状态机枚举：状态迁移规则集中封装（原来散落在调度器/客户端/管控的 String 常量比对全部收编于此）。
 * <p>
 * 状态机全景：
 * <pre>
 *   PENDING ──认领──► RUNNING ──成功──► SUCCESS（终态）
 *     ▲                  │
 *     │                  ├──失败(未超限)──► PENDING（指数退避重试）
 *     └──悬挂回收────────┘
 *                        └──失败(超限)───► DEAD（终态，管控可 retry 打回）
 * </pre>
 * 管控处置是人工覆盖通道：DEAD→PENDING（retry）、RUNNING→PENDING（requeue）、任意→DEAD（kill），
 * 不受自动迁移规则约束。
 *
 * @author muyi
 * @date 2026/10/25
 */
public enum CommandStatus {

    /** 待执行：已落库未认领（或退避重试中 / 悬挂回收后重新入队） */
    PENDING("PENDING", false),

    /** 执行中：某实例已认领持有令牌（悬挂判定锚点） */
    RUNNING("RUNNING", false),

    /** 成功（终态）：执行器正常返回 */
    SUCCESS("SUCCESS", true),

    /** 死信（终态）：重试超限；管控端点可 retry 打回 PENDING */
    DEAD("DEAD", true);

    /** 数据库存储码（与表 status 列值一致，大写单词） */
    private final String code;

    /** 是否终态（终态不再被调度器认领） */
    private final boolean terminal;

    /** code → 枚举查找表（静态初始化一次） */
    private static final Map<String, CommandStatus> BY_CODE = Stream.of(values())
            .collect(Collectors.toMap(CommandStatus::getCode, Function.identity()));

    /**
     * 枚举构造。
     *
     * @param code     存储码（与 DB 列值一致）
     * @param terminal true 表示终态
     */
    CommandStatus(String code, boolean terminal) {
        this.code = code;
        this.terminal = terminal;
    }

    /**
     * 读取存储码。
     *
     * @return 与表列值一致的字符串
     */
    public String getCode() {
        return code;
    }

    /**
     * 是否终态（SUCCESS / DEAD）——终态命令不会被调度器认领。
     *
     * @return true 表示不会再发生自动迁移
     */
    public boolean isTerminal() {
        return terminal;
    }

    /**
     * 自动迁移合法性（调度器/客户端视角的状态机守卫）。
     * 合法自动迁移：PENDING→RUNNING（认领）、RUNNING→SUCCESS/PENDING/DEAD（执行结果）。
     * 管控处置（retry/requeue/kill）是人工通道，不走本判断。
     *
     * @param target 目标状态
     * @return true 表示调度链路允许该迁移
     */
    public boolean canAutoTransitionTo(CommandStatus target) {
        return switch (this) {
            case PENDING -> target == RUNNING;
            case RUNNING -> target == SUCCESS || target == PENDING || target == DEAD;
            // 终态无自动出口（DEAD→PENDING 仅管控 retry 人工触发）
            case SUCCESS, DEAD -> false;
        };
    }

    /**
     * 按存储码解析枚举（行映射用）。
     *
     * @param code 表列值（大小写不敏感）
     * @return 对应枚举
     * @throws IllegalArgumentException 未知码（数据被外部篡改的异常态，fail-fast 暴露）
     */
    public static CommandStatus fromCode(String code) {
        CommandStatus s = BY_CODE.get(code);
        if (s == null) {
            throw new IllegalArgumentException("未知命令状态码: " + code);
        }
        return s;
    }
}
