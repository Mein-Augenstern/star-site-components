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

/**
 * 时间源抽象（async-command 模块自持，与 sequence 模块解耦）。
 * 生产用 {@link #system()}；测试注入假时钟确定性构造延迟/超时场景。
 *
 * @author muyi
 * @since 2026-10
 */
@FunctionalInterface
public interface TimeSource {

    java.time.LocalDateTime now();

    static TimeSource system() {
        return java.time.LocalDateTime::now;
    }
}
