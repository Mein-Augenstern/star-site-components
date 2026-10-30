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
package com.xp.starsite.asynccmd.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * 管控鉴权测试：内置静态 Token 守卫（通过/拒绝/缺头/空白配置 fail-fast）
 * 与装配层语义（自定义守卫优先、admin-token 触发内置守卫）。
 *
 * @author muyi
 * @date 2026/10/25
 */
class AsyncCommandAdminGuardTest {

    /** 构造带 token 请求头的 mock 请求 */
    private static HttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (token != null) {
            request.addHeader(StaticTokenAdminGuard.HEADER_NAME, token);
        }
        return request;
    }

    @Test
    void 正确token_放行() {
        StaticTokenAdminGuard guard = new StaticTokenAdminGuard("s3cret");
        assertThatCode(() -> guard.check(requestWithToken("s3cret"))).doesNotThrowAnyException();
    }

    @Test
    void 错误token_抛401() {
        StaticTokenAdminGuard guard = new StaticTokenAdminGuard("s3cret");
        assertThatThrownBy(() -> guard.check(requestWithToken("wrong")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 缺请求头_抛401() {
        StaticTokenAdminGuard guard = new StaticTokenAdminGuard("s3cret");
        assertThatThrownBy(() -> guard.check(requestWithToken(null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void 空白token配置_构造期fail_fast() {
        assertThatThrownBy(() -> new StaticTokenAdminGuard("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非空");
    }

    /** 装配层语义测试（guard Bean 的条件装配与让位） */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    com.xp.starsite.asynccmd.AsyncCommandAutoConfiguration.class));

    @Test
    void 配置admin_token_装配内置守卫() {
        runner.withPropertyValues(
                        "starsite.async-command.mode=standalone",
                        "starsite.async-command.admin-enabled=true",
                        "starsite.async-command.admin-token=t-ok")
                .run(ctx ->
                        assertThat(ctx).hasSingleBean(AsyncCommandAdminGuard.class));
    }

    @Test
    void 自定义守卫Bean_内置守卫让位() {
        AsyncCommandAdminGuard custom = request -> { /* 自定义放行 */ };
        runner.withPropertyValues(
                        "starsite.async-command.mode=standalone",
                        "starsite.async-command.admin-enabled=true",
                        "starsite.async-command.admin-token=t-ok")
                .withBean("customGuard", AsyncCommandAdminGuard.class, () -> custom)
                .run(ctx ->
                        assertThat(ctx.getBean(AsyncCommandAdminGuard.class)).isSameAs(custom));
    }

    @Test
    void 未配token且无自定义守卫_不装配守卫Bean_控制器兜底ALLOW_ALL() {
        runner.withPropertyValues(
                        "starsite.async-command.mode=standalone",
                        "starsite.async-command.admin-enabled=true")
                .run(ctx ->
                        assertThat(ctx).doesNotHaveBean(AsyncCommandAdminGuard.class));
    }
}
