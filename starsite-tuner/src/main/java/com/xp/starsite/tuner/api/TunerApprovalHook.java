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
 * 审批钩子 SPI（变更治理——V2）：所有参数变更（窗口切换/手动/自适应）应用前回调，
 * 返回非空拒绝理由即跳过应用（WARN 留痕；审计只记实际变更，拒绝尝试由业务侧流程记录）。
 *
 * <p>信贷语义：双人复核、变更窗口管控（还款日禁改）、预案依据说明——流程是业务的，
 * 组件只提供"变更前的最后一道门"。
 *
 * @author muyi
 * @date 2026/11/14
 */
public interface TunerApprovalHook {

    /**
     * 审批一次变更。
     *
     * @param request 变更请求
     * @return 拒绝理由（非空=DENY）；empty=ALLOW
     */
    Optional<String> approve(TunerChangeRequest request);
}
