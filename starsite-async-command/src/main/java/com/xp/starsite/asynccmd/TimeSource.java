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
 * 为什么存在：把"取当前时间"抽象出来，测试可注入假时钟，确定性构造延迟到期/悬挂超时场景（不依赖真实等待）。
 * 生产用 {@link #system()}；测试注入假时钟确定性构造延迟/超时场景。
 * <p>
 * 线程安全性：实现应为无状态或线程安全（system 实现无状态，任意并发安全）。
 *
 * @author muyi
 * @date 2026/10/25
 */
@FunctionalInterface
public interface TimeSource {

    /**
     * 返回当前时间。
     *
     * @return 当前时间；system 实现为 {@code LocalDateTime.now()}，测试实现由假时钟推进
     */
    java.time.LocalDateTime now();

    /**
     * 系统时钟实现。
     *
     * @return 委托 {@code LocalDateTime.now()} 的时间源（无状态，线程安全）
     */
    static TimeSource system() {
        return java.time.LocalDateTime::now;
    }
}
