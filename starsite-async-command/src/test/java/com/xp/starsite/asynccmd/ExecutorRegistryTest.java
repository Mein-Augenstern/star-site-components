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
package com.xp.starsite.asynccmd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 执行器注册表单测：路由查找、类型集合、重复 type 启动期拒绝。
 *
 * @author muyi
 * @since 2026-10
 */
class ExecutorRegistryTest {

    private static AsyncCommandExecutor exec(String type) {
        return new AsyncCommandExecutor() {
            @Override public String type() { return type; }
            @Override public void execute(AsyncCommand cmd) { }
        };
    }

    @Test
    void 按类型查找_命中与未命中() {
        ExecutorRegistry registry = new ExecutorRegistry(() -> List.of(exec("a"), exec("b")));
        assertThat(registry.find("a")).isNotNull();
        assertThat(registry.find("b")).isNotNull();
        assertThat(registry.find("c")).isNull();
        assertThat(registry.types()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void 重复type_启动期拒绝且信息含冲突键() {
        ExecutorRegistry registry = new ExecutorRegistry(() -> List.of(exec("dup"), exec("dup")));
        assertThatThrownBy(registry::types)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dup");
    }

    @Test
    void 空执行器集合_合法_全部命令按无执行器重试() {
        ExecutorRegistry registry = new ExecutorRegistry(() -> List.of());
        assertThat(registry.find("anything")).isNull();
        assertThat(registry.types()).isEmpty();
    }

    @Test
    void 供应器返回null_按空集处理() {
        ExecutorRegistry registry = new ExecutorRegistry(() -> null);
        assertThat(registry.types()).isEmpty();
    }
}
