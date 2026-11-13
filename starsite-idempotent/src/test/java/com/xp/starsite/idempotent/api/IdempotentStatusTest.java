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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 幂等状态机测试：终态判定 / 回放判定 / 列值解析（结构漂移提前暴露）。
 *
 * @author muyi
 * @date 2026/11/12
 */
class IdempotentStatusTest {

    /** 终态判定：DONE/FAILED 终态，PROCESSING 非终态 */
    @Test
    void terminal() {
        assertThat(IdempotentStatus.PROCESSING.isTerminal()).isFalse();
        assertThat(IdempotentStatus.DONE.isTerminal()).isTrue();
        assertThat(IdempotentStatus.FAILED.isTerminal()).isTrue();
    }

    /** 回放判定：仅 DONE 可回放（FAILED 释放键、PROCESSING 在飞） */
    @Test
    void replayable() {
        assertThat(IdempotentStatus.DONE.isReplayable()).isTrue();
        assertThat(IdempotentStatus.PROCESSING.isReplayable()).isFalse();
        assertThat(IdempotentStatus.FAILED.isReplayable()).isFalse();
    }

    /** 列值解析与未知值快速失败 */
    @Test
    void fromCodeRoundTripAndRejectsUnknown() {
        for (IdempotentStatus s : IdempotentStatus.values()) {
            assertThat(IdempotentStatus.fromCode(s.name())).isEqualTo(s);
        }
        assertThatThrownBy(() -> IdempotentStatus.fromCode("EXPIRED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXPIRED");
    }
}
