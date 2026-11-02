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
package com.xp.starsite.schedule.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xp.starsite.schedule.api.MisfirePolicy;
import com.xp.starsite.schedule.api.StarsiteScheduled;
import com.xp.starsite.schedule.trigger.DailyAtTrigger;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * 注册表与任务定义测试（锁名规范 / 重名快速失败 / 校验）。
 *
 * @author muyi
 * @date 2026/11/02
 */
class ScheduleTaskRegistryTest {

    private static ScheduledTaskDef task(String name) {
        return new ScheduledTaskDef(name, new DailyAtTrigger(8, 0, ZoneId.of("Asia/Shanghai")),
                MisfirePolicy.SKIP, true, () -> { });
    }

    @Test
    void lockNameFollowsProtocol() {
        // 锁名协议：sch:<任务名>（跨语言锚点，invest Python 侧同规范）
        assertThat(task("invest-pull").getLockName())
                .isEqualTo(StarsiteScheduled.LOCK_PREFIX + "invest-pull");
    }

    @Test
    void duplicateNameFailsFast() {
        ScheduleTaskRegistry registry = new ScheduleTaskRegistry();
        registry.register(task("a"));
        assertThatThrownBy(() -> registry.register(task("a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a");
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void registerAndFindInOrder() {
        ScheduleTaskRegistry registry = new ScheduleTaskRegistry();
        registry.register(task("b"));
        registry.register(task("a"));
        assertThat(registry.all()).extracting(ScheduledTaskDef::getName).containsExactly("b", "a");
        assertThat(registry.find("a")).isPresent();
        assertThat(registry.find("missing")).isEmpty();
    }

    @Test
    void defValidation() {
        assertThatThrownBy(() -> task("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledTaskDef(null,
                new DailyAtTrigger(8, 0, ZoneId.of("Asia/Shanghai")), MisfirePolicy.SKIP, true, () -> { }))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ScheduledTaskDef("x", null,
                MisfirePolicy.SKIP, true, () -> { }))
                .isInstanceOf(NullPointerException.class);
    }
}
