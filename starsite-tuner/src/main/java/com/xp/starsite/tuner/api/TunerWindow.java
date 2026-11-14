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
 * 参数窗口（tuner_window 行）：某池在某 cron 区间内应用某预案。
 *
 * <p>信贷语义示例：还款日代扣扩容——{@code pool=channel-daikou, preset=repayday,
 * startCron="0 0 0 10 * ?", endCron="0 0 0 16 * ?"}（每月 10 日零点起到 16 日零点）。
 *
 * <p>命中判定（纯时间函数，{@code t} 时刻）：
 * {@code startCron.nextAfter(t) > endCron.nextAfter(t)}——下一次开始晚于下一次结束
 * ⟺ 上一次结束早于上一次开始 ⟺ 当前在窗口内。只需 nextAfter 无需反推 prev。
 * 多窗口命中取 endCron.nextAfter 最远者（剩余最长）；时区取组件配置（默认东八，
 * 家族 UTC 陷阱惯例）。
 *
 * @author muyi
 * @date 2026/11/14
 */
public class TunerWindow {

    /** 窗口 id（sequence 发号） */
    private final long id;

    /** 池名 */
    private final String poolName;

    /** 窗口内应用的预案名 */
    private final String presetName;

    /** 窗口开始 cron（6 字段，schedule 解析器同语义） */
    private final String startCron;

    /** 窗口结束 cron */
    private final String endCron;

    /**
     * 业务形态构造（新建窗口 id=0，落库时由 store 发号）。
     *
     * @param poolName   池名
     * @param presetName 预案名
     * @param startCron  开始 cron
     * @param endCron    结束 cron
     */
    public TunerWindow(String poolName, String presetName, String startCron, String endCron) {
        this(0L, poolName, presetName, startCron, endCron);
    }

    /**
     * 全参构造（存储层装配用；Long 形态适配 MyBatis 映射）。
     *
     * @param id         窗口 id
     * @param poolName   池名
     * @param presetName 预案名
     * @param startCron  开始 cron
     * @param endCron    结束 cron
     */
    public TunerWindow(Long id, String poolName, String presetName,
                       String startCron, String endCron) {
        this.id = id == null ? 0L : id;
        this.poolName = poolName;
        this.presetName = presetName;
        this.startCron = startCron;
        this.endCron = endCron;
    }

    /**
     * 获取窗口 id。
     *
     * @return 窗口 id
     */
    public long getId() {
        return id;
    }

    /**
     * 获取池名。
     *
     * @return 池名
     */
    public String getPoolName() {
        return poolName;
    }

    /**
     * 获取预案名。
     *
     * @return 预案名
     */
    public String getPresetName() {
        return presetName;
    }

    /**
     * 获取开始 cron。
     *
     * @return 开始 cron
     */
    public String getStartCron() {
        return startCron;
    }

    /**
     * 获取结束 cron。
     *
     * @return 结束 cron
     */
    public String getEndCron() {
        return endCron;
    }
}
