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
package com.xp.starsite.tuner.api;

import java.util.Optional;

/**
 * 自适应规则 SPI（健康度联动——V2）：业务侧按池快照决定是否切换预案
 * （信贷语义：通道 RT 恶化 → 降并发保护性回退；恢复 → 回升。规则是业务的，骨架是组件的）。
 *
 * <p>优先级：自适应命中（冷却期内）&gt; 窗口 &gt; 默认绑定 &gt; 基线。引擎每轮 poll 对每池
 * 顺序求值全部规则，首个返回非空者生效（source=ADAPTIVE，冷却窗口防抖动）。
 *
 * @author muyi
 * @date 2026/11/14
 */
public interface TunerAdaptiveRule {

    /**
     * 决策：按池快照给出应切换的预案。
     *
     * @param snapshot 池快照（实际参数+活跃度+当前目标）
     * @return 目标预案名；不切换为 empty
     */
    Optional<String> decide(PoolSnapshot snapshot);
}
