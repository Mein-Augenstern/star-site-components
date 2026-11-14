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
 * 一次阈值告警（引擎每轮 poll 对每池评估——越阈值即经 {@link TunerAlarmHook} 上抛，
 * 同池同类型在冷却窗口内只告警一次）。
 *
 * @param poolName  池名
 * @param type      告警类型
 * @param value     当前值（比例 0.0~1.0+）
 * @param threshold 触发阈值
 * @author muyi
 * @date 2026/11/14
 */
public record TunerAlarm(String poolName, Type type, double value, double threshold) {

    /** 告警类型 */
    public enum Type {

        /** 活跃度 = 活跃线程数 / 最大线程数（≥ 阈值告警——线程不够用前的预警） */
        ACTIVE_RATIO,

        /** 队列水位 = 队列积压 / 队列容量（仅可变容量/有界队列；无界不评估） */
        QUEUE_RATIO
    }

    /**
     * 获取池名。
     *
     * @return 池名
     */
    public String poolName() {
        return poolName;
    }

    /**
     * 获取告警类型。
     *
     * @return 类型
     */
    public Type type() {
        return type;
    }

    /**
     * 获取当前值。
     *
     * @return 比例值
     */
    public double value() {
        return value;
    }

    /**
     * 获取触发阈值。
     *
     * @return 阈值
     */
    public double threshold() {
        return threshold;
    }
}
