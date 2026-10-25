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

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 内置静态 Token 守卫：校验请求头 {@value #HEADER_NAME} 与配置 token 是否一致。
 * <p>
 * 比对方式：{@link MessageDigest#isEqual(byte[], byte[])} 常量时间比较——
 * 逐字节比对耗时与长度脱敏，防时序侧信道逐位猜测 token。
 * 未携带请求头、或 token 不一致均抛 401。
 * <p>
 * 生效条件：配置 {@code starsite.async-command.admin-token=非空} 且应用未自定义
 * {@link AsyncCommandAdminGuard} Bean（自定义守卫优先级更高）。
 * 适用边界：单服务/小团队的够用方案；多服务统一鉴权建议实现自定义守卫接内部认证体系。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class StaticTokenAdminGuard implements AsyncCommandAdminGuard {

    /** 约定的 token 请求头名 */
    public static final String HEADER_NAME = "X-Starsite-Admin-Token";

    /** 配置的合法 token（构造后不变） */
    private final String expectedToken;

    /**
     * 构造。
     *
     * @param expectedToken 合法 token（非空；装配层保证非空，这里防御再校验）
     * @throws IllegalArgumentException token 为 null 或空白（配置错误应启动期暴露）
     */
    public StaticTokenAdminGuard(String expectedToken) {
        if (expectedToken == null || expectedToken.isBlank()) {
            throw new IllegalArgumentException("admin-token 必须为非空字符串");
        }
        this.expectedToken = expectedToken;
    }

    /**
     * 校验请求头 token：缺失或不符抛 401（常量时间比对防时序侧信道）。
     *
     * @param request 当前 HTTP 请求
     * @throws ResponseStatusException 401（UNAUTHORIZED）——token 缺失或不匹配
     */
    @Override
    public void check(HttpServletRequest request) {
        String provided = request.getHeader(HEADER_NAME);
        if (provided == null || !constantTimeEquals(expectedToken, provided)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "管控端点鉴权失败：缺少或错误的请求头 " + HEADER_NAME);
        }
    }

    /**
     * 常量时间字符串相等比较（转字节后走 MessageDigest.isEqual）。
     *
     * @param a 期望值（配置 token）
     * @param b 实际值（请求头 token）
     * @return 相等返回 true；否则 false（耗时与内容长度弱相关，防逐位猜测）
     */
    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
