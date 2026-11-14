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
package com.xp.starsite.tuner.core;
import com.xp.starsite.tuner.api.TunerAdaptiveRule;
import com.xp.starsite.tuner.api.TunerAlarmHook;
import com.xp.starsite.tuner.api.TunerApprovalHook;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * 引擎 V2 选项（钩子/阈值/冷却——starter 从 Bean+Properties 装配；测试直接构造）。
 * 全部字段不可变；无钩子字段为 null = 不启用该能力（Noop 语义，不建空实现类）。
 *
 * @author muyi
 * @date 2026/11/14
 */
public final class TunerOptions {

    /** 审批钩子（null=不启用——变更直接应用） */
    private final TunerApprovalHook approvalHook;

    /** 告警钩子（null=不启用阈值告警） */
    private final TunerAlarmHook alarmHook;

    /** 自适应规则（首个命中生效；空列表=不启用） */
    private final List<TunerAdaptiveRule> adaptiveRules;

    /** 活跃度告警阈值（0.0~1.0） */
    private final double alarmActiveRatio;

    /** 队列水位告警阈值（0.0~1.0） */
    private final double alarmQueueRatio;

    /** 告警冷却窗口（同池同类型防风暴） */
    private final Duration alarmCooldown;

    /** 自适应冷却窗口（防规则抖动） */
    private final Duration adaptiveCooldown;

    /** 审计保留天数（<=0 = 不清理——合规默认长保留） */
    private final int auditRetentionDays;

    /** 审计清理批量 */
    private final int auditPurgeBatch;

    /**
     * 家族缺省选项（无钩子；阈值 0.9/0.8；冷却 300s/120s；retention 关）。
     *
     * @return 缺省选项
     */
    public static TunerOptions defaults() {
        return builder().build();
    }

    /**
     * 建造器入口。
     *
     * @return 建造器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 全参构造（builder 专用）。
     *
     * @param approvalHook       审批钩子
     * @param alarmHook          告警钩子
     * @param adaptiveRules      自适应规则
     * @param alarmActiveRatio   活跃度阈值
     * @param alarmQueueRatio    队列水位阈值
     * @param alarmCooldown      告警冷却
     * @param adaptiveCooldown   自适应冷却
     * @param auditRetentionDays 审计保留天数
     * @param auditPurgeBatch    审计清理批量
     */
    private TunerOptions(TunerApprovalHook approvalHook, TunerAlarmHook alarmHook,
                         List<TunerAdaptiveRule> adaptiveRules, double alarmActiveRatio,
                         double alarmQueueRatio, Duration alarmCooldown,
                         Duration adaptiveCooldown, int auditRetentionDays,
                         int auditPurgeBatch) {
        this.approvalHook = approvalHook;
        this.alarmHook = alarmHook;
        this.adaptiveRules = adaptiveRules;
        this.alarmActiveRatio = alarmActiveRatio;
        this.alarmQueueRatio = alarmQueueRatio;
        this.alarmCooldown = alarmCooldown;
        this.adaptiveCooldown = adaptiveCooldown;
        this.auditRetentionDays = auditRetentionDays;
        this.auditPurgeBatch = auditPurgeBatch;
    }

    /**
     * 获取审批钩子。
     *
     * @return 审批钩子；null=不启用
     */
    public TunerApprovalHook getApprovalHook() {
        return approvalHook;
    }

    /**
     * 获取告警钩子。
     *
     * @return 告警钩子；null=不启用
     */
    public TunerAlarmHook getAlarmHook() {
        return alarmHook;
    }

    /**
     * 获取自适应规则列表。
     *
     * @return 规则列表（不可变视图）
     */
    public List<TunerAdaptiveRule> getAdaptiveRules() {
        return adaptiveRules;
    }

    /**
     * 获取活跃度告警阈值。
     *
     * @return 阈值
     */
    public double getAlarmActiveRatio() {
        return alarmActiveRatio;
    }

    /**
     * 获取队列水位告警阈值。
     *
     * @return 阈值
     */
    public double getAlarmQueueRatio() {
        return alarmQueueRatio;
    }

    /**
     * 获取告警冷却窗口。
     *
     * @return 冷却时长
     */
    public Duration getAlarmCooldown() {
        return alarmCooldown;
    }

    /**
     * 获取自适应冷却窗口。
     *
     * @return 冷却时长
     */
    public Duration getAdaptiveCooldown() {
        return adaptiveCooldown;
    }

    /**
     * 获取审计保留天数。
     *
     * @return 保留天数（<=0 不清理）
     */
    public int getAuditRetentionDays() {
        return auditRetentionDays;
    }

    /**
     * 获取审计清理批量。
     *
     * @return 批量
     */
    public int getAuditPurgeBatch() {
        return auditPurgeBatch;
    }

    /** 建造器（家族未引 Lombok——手写） */
    public static final class Builder {

        /** 审批钩子 */
        private TunerApprovalHook approvalHook;

        /** 告警钩子 */
        private TunerAlarmHook alarmHook;

        /** 自适应规则 */
        private List<TunerAdaptiveRule> adaptiveRules = Arrays.asList();

        /** 活跃度阈值 */
        private double alarmActiveRatio = 0.9;

        /** 队列水位阈值 */
        private double alarmQueueRatio = 0.8;

        /** 告警冷却 */
        private Duration alarmCooldown = Duration.ofMinutes(5);

        /** 自适应冷却 */
        private Duration adaptiveCooldown = Duration.ofMinutes(2);

        /** 审计保留天数 */
        private int auditRetentionDays = 0;

        /** 审计清理批量 */
        private int auditPurgeBatch = 500;

        /**
         * 设置审批钩子。
         *
         * @param hook 审批钩子
         * @return 建造器
         */
        public Builder approvalHook(TunerApprovalHook hook) {
            this.approvalHook = hook;
            return this;
        }

        /**
         * 设置告警钩子。
         *
         * @param hook 告警钩子
         * @return 建造器
         */
        public Builder alarmHook(TunerAlarmHook hook) {
            this.alarmHook = hook;
            return this;
        }

        /**
         * 设置自适应规则列表。
         *
         * @param rules 规则列表
         * @return 建造器
         */
        public Builder adaptiveRules(List<TunerAdaptiveRule> rules) {
            this.adaptiveRules = rules == null ? Arrays.asList() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(rules));
            return this;
        }

        /**
         * 设置活跃度告警阈值。
         *
         * @param ratio 阈值
         * @return 建造器
         */
        public Builder alarmActiveRatio(double ratio) {
            this.alarmActiveRatio = ratio;
            return this;
        }

        /**
         * 设置队列水位告警阈值。
         *
         * @param ratio 阈值
         * @return 建造器
         */
        public Builder alarmQueueRatio(double ratio) {
            this.alarmQueueRatio = ratio;
            return this;
        }

        /**
         * 设置告警冷却窗口。
         *
         * @param duration 冷却时长
         * @return 建造器
         */
        public Builder alarmCooldown(Duration duration) {
            this.alarmCooldown = duration;
            return this;
        }

        /**
         * 设置自适应冷却窗口。
         *
         * @param duration 冷却时长
         * @return 建造器
         */
        public Builder adaptiveCooldown(Duration duration) {
            this.adaptiveCooldown = duration;
            return this;
        }

        /**
         * 设置审计保留天数。
         *
         * @param days 保留天数（<=0 不清理）
         * @return 建造器
         */
        public Builder auditRetentionDays(int days) {
            this.auditRetentionDays = days;
            return this;
        }

        /**
         * 设置审计清理批量。
         *
         * @param batch 批量
         * @return 建造器
         */
        public Builder auditPurgeBatch(int batch) {
            this.auditPurgeBatch = batch;
            return this;
        }

        /**
         * 构建。
         *
         * @return 选项实例
         */
        public TunerOptions build() {
            return new TunerOptions(approvalHook, alarmHook, adaptiveRules, alarmActiveRatio,
                    alarmQueueRatio, alarmCooldown, adaptiveCooldown, auditRetentionDays,
                    auditPurgeBatch);
        }
    }
}
