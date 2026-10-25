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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 命令状态机枚举测试：迁移守卫、终态判定、存储码解析。
 *
 * @author muyi
 * @date 2026/10/25
 */
class CommandStatusTest {

    /** 认领：PENDING→RUNNING 合法 */
    @Test
    void 认领迁移_合法() {
        assertThat(CommandStatus.PENDING.canAutoTransitionTo(CommandStatus.RUNNING)).isTrue();
    }

    /** 执行结果：RUNNING 三出口全部合法 */
    @Test
    void 执行结果迁移_三出口合法() {
        assertThat(CommandStatus.RUNNING.canAutoTransitionTo(CommandStatus.SUCCESS)).isTrue();
        assertThat(CommandStatus.RUNNING.canAutoTransitionTo(CommandStatus.PENDING)).isTrue();
        assertThat(CommandStatus.RUNNING.canAutoTransitionTo(CommandStatus.DEAD)).isTrue();
    }

    /** 终态无自动出口：SUCCESS/DEAD 的任何自动迁移都不合法（管控人工通道除外） */
    @Test
    void 终态_无自动出口() {
        for (CommandStatus from : new CommandStatus[] {CommandStatus.SUCCESS, CommandStatus.DEAD}) {
            for (CommandStatus to : CommandStatus.values()) {
                assertThat(from.canAutoTransitionTo(to))
                        .as("%s→%s 不应有自动迁移", from, to).isFalse();
            }
        }
    }

    /** 非法迁移：PENDING 不能直接跳 SUCCESS/DEAD（必须先认领） */
    @Test
    void 跳态迁移_不合法() {
        assertThat(CommandStatus.PENDING.canAutoTransitionTo(CommandStatus.SUCCESS)).isFalse();
        assertThat(CommandStatus.PENDING.canAutoTransitionTo(CommandStatus.DEAD)).isFalse();
        assertThat(CommandStatus.PENDING.canAutoTransitionTo(CommandStatus.PENDING)).isFalse();
    }

    /** 终态标记：SUCCESS 与 DEAD 是终态，PENDING/RUNNING 不是 */
    @Test
    void 终态标记() {
        assertThat(CommandStatus.SUCCESS.isTerminal()).isTrue();
        assertThat(CommandStatus.DEAD.isTerminal()).isTrue();
        assertThat(CommandStatus.PENDING.isTerminal()).isFalse();
        assertThat(CommandStatus.RUNNING.isTerminal()).isFalse();
    }

    /** 存储码解析：往返一致；未知码 fail-fast */
    @Test
    void 存储码解析() {
        for (CommandStatus s : CommandStatus.values()) {
            assertThat(CommandStatus.fromCode(s.getCode())).isSameAs(s);
        }
        assertThatThrownBy(() -> CommandStatus.fromCode("BOGUS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BOGUS");
    }

    /** AsyncCommand 双轨访问器：String 兼容形态与枚举形态一致 */
    @Test
    void asyncCommand_双轨访问器() {
        AsyncCommand cmd = new AsyncCommand();
        cmd.setStatus("RUNNING");
        assertThat(cmd.getStatus()).isEqualTo("RUNNING");
        assertThat(cmd.getStatusEnum()).isEqualTo(CommandStatus.RUNNING);

        cmd.setStatusEnum(CommandStatus.SUCCESS);
        assertThat(cmd.getStatus()).isEqualTo("SUCCESS");
        assertThat(cmd.getStatusEnum()).isEqualTo(CommandStatus.SUCCESS);

        // 兼容常量仍指向枚举 code
        assertThat(AsyncCommand.PENDING).isEqualTo("PENDING");
        assertThat(AsyncCommand.DEAD).isEqualTo("DEAD");
    }

    /** 未知码经 setStatus 装载也 fail-fast（外部数据篡改的异常态暴露） */
    @Test
    void asyncCommand_未知码装载_failFast() {
        AsyncCommand cmd = new AsyncCommand();
        assertThatThrownBy(() -> cmd.setStatus("HACKED"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
