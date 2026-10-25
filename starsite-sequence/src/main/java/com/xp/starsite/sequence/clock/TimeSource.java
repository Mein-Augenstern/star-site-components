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
package com.xp.starsite.sequence.clock;

/**
 * 时间源抽象：为雪花生成器提供可注入的当前毫秒。
 * <p>
 * 为什么存在：把"读时钟"这一个外部 IO 点抽出来——生产用 {@link #system()} 走系统时钟，
 * 测试注入假时钟即可确定性构造时钟回拨/同毫秒场景，不依赖真实时间流逝。
 *
 * @author muyi
 * @date 2026/10/25
 */
@FunctionalInterface
public interface TimeSource {

    /**
     * 获取当前时间戳。
     *
     * @return 当前毫秒数，与 {@link System#currentTimeMillis()} 同基准同单位
     */
    long currentTimeMillis();

    /**
     * 系统时钟实现工厂：直接委托 {@link System#currentTimeMillis()}。
     *
     * @return 使用系统时钟的 TimeSource 实例（无状态，可重复获取共享）
     */
    static TimeSource system() {
        return System::currentTimeMillis;
    }
}
