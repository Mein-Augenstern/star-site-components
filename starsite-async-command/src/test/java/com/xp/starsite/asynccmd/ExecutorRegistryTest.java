package com.xp.starsite.asynccmd;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
