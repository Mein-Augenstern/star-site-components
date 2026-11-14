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
package com.xp.starsite.tuner.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内置 token 守卫：校验请求头 {@code X-Starsite-Admin-Token}，
 * <b>常量时间比对</b>防时序侧信道（async StaticTokenAdminGuard 同款实现）。
 *
 * @author muyi
 * @date 2026/11/14
 */
public class StaticTokenAdminGuard implements TunerAdminGuard {

    /** 请求头名 */
    public static final String HEADER = "X-Starsite-Admin-Token";

    /** 期望 token 的 SHA-256（不持有明文——防 dump 泄露） */
    private final byte[] expectedDigest;

    /**
     * 构造。
     *
     * @param token 配置的明文 token
     */
    public StaticTokenAdminGuard(String token) {
        try {
            this.expectedDigest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** {@inheritDoc} 常量时间比对（长度差异前先哈希抹平） */
    @Override
    public boolean allow(java.util.Map<String, String> headers) {
        String provided = headers.get(HEADER);
        if (provided == null) {
            return false;
        }
        byte[] actual;
        try {
            actual = MessageDigest.getInstance("SHA-256")
                    .digest(provided.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
        return MessageDigest.isEqual(expectedDigest, actual);
    }
}
