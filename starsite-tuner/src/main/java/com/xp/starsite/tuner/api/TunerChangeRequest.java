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

import java.time.LocalDateTime;

/**
 * 待审批的参数变更请求（审批钩子 SPI 的入参——变更前回调，DENY 即跳过应用）。
 *
 * @param poolName   池名
 * @param presetName 目标预案名
 * @param source     触发源（SCHEDULE/MANUAL/ADAPTIVE/STARTUP——审批是否放行窗口切换由业务定）
 * @param oldCore    旧核心数
 * @param oldMax     旧最大数
 * @param oldKeep    旧保活秒
 * @param oldQueue   旧队列容量（null=不涉及）
 * @param newCore    新核心数
 * @param newMax     新最大数
 * @param newKeep    新保活秒
 * @param newQueue   新队列容量（null=不涉及）
 * @param host       发起实例
 * @param occurredAt 请求时刻
 * @author muyi
 * @date 2026/11/14
 */
public record TunerChangeRequest(String poolName, String presetName,
                                 TunerAuditEntry.Source source,
                                 int oldCore, int oldMax, int oldKeep, Integer oldQueue,
                                 int newCore, int newMax, int newKeep, Integer newQueue,
                                 String host, LocalDateTime occurredAt) {

    /**
     * 获取池名。
     *
     * @return 池名
     */
    public String poolName() {
        return poolName;
    }
}
