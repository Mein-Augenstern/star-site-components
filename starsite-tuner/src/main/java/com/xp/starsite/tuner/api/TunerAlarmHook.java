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

/**
 * 告警钩子 SPI（注册 Bean 即接管——引擎每轮 poll 评估阈值，越限上抛；
 * 同池同类型在告警冷却窗口内只触发一次，防风暴）。
 *
 * <p>业务侧典型实现：推钉钉/飞书群（信贷"拒绝前预警"方法论——告警在线程不够用之前，
 * 而不是拒绝之后）。
 *
 * @author muyi
 * @date 2026/11/14
 */
public interface TunerAlarmHook {

    /**
     * 处理一次告警（异常吞掉记 WARN——告警通道故障不许影响治理轮询）。
     *
     * @param alarm 告警
     */
    void onAlarm(TunerAlarm alarm);
}
