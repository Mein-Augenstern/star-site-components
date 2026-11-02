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
package com.xp.starsite.schedule.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明分布式恰一定时任务：每个实例都按同一时刻表触发，但每个触发点<b>全集群只执行一次</b>
 * （未获锁实例写 SKIPPED_LOCKED 流水后静默跳过）。
 *
 * <p>触发器三选一（都配则校验失败快速暴露）：{@link #cron()} / {@link #dailyAt()} /
 * {@link #fixedDelaySeconds()}。锁名为 {@code sch:<任务名>}（{@value #LOCK_PREFIX} 前缀
 * 与业务手工锁隔离；跨语言协议见 docs/schedule.md §8）。
 *
 * <p><b>幂等契约</b>：决策层 fail-open——锁存储异常时放行执行，极端下可能双跑。
 * 任务体必须幂等（数据面幂等写入是标配，而非依赖调度器承诺不重）。
 *
 * <p>示例（backend ReminderScanTask 的组件化形态）：
 * <pre>{@code
 * @StarsiteScheduled(name = "reminder-scan", cron = "0 0 8 * * ?")
 * public void scan() { reminderService.scanAndNotify(); }
 * }</pre>
 *
 * @author muyi
 * @date 2026/11/02
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface StarsiteScheduled {

    /** 锁名前缀（sch: schedule 缩写——跨语言协议锚点，invest Python 侧同规范） */
    String LOCK_PREFIX = "sch:";

    /**
     * 任务名（全应用唯一；重复注册快速失败）。
     * <p>缺省：声明类简单名#方法名（如 {@code ReminderScanTask#scan}）。
     *
     * @return 任务名
     */
    String name() default "";

    /**
     * 触发器①：6 字段 cron（秒 分 时 日 月 周，Quartz 风格含 {@code ?} 兼容与月/周英文名）。
     * <p>示例：{@code 0 0 8 * * ?}（每天 08:00:00）、{@code 0 0/5 * * * ?}（每 5 分钟）。
     *
     * @return cron 表达式；空串表示不用 cron 触发器
     */
    String cron() default "";

    /**
     * 触发器②：每天固定时刻（HH:mm）——覆盖日级任务的 90% 场景（invest S09 每晚 20:00 拉数形态）。
     *
     * @return 时刻字符串；空串表示不用 dailyAt 触发器
     */
    String dailyAt() default "";

    /**
     * 触发器③：固定间隔（秒，上次<b>完成后</b>起算——慢任务不堆积）。&le;0 视为未配置。
     *
     * @return 间隔秒数
     */
    long fixedDelaySeconds() default -1;

    /**
     * 触发时区。默认 {@value #DEFAULT_ZONE}——容器 UTC 陷阱（hour=20 实际凌晨 4 点触发）
     * 的产品化默认值，实证出处见 docs/schedule.md §6。
     *
     * @return 时区 ID
     */
    String zone() default DEFAULT_ZONE;

    /** 默认时区（产品语义：A 股/基金日级周期） */
    String DEFAULT_ZONE = "Asia/Shanghai";

    /**
     * 错过策略（全部实例都停机跨越了触发点）：
     * <ul>
     *   <li>{@link MisfirePolicy#SKIP}：错过就错过（默认——幂等数据任务最安全）</li>
     *   <li>{@link MisfirePolicy#RUN_ONCE}：启动时对比流水最近成功触发点与时刻表推算，
     *       缺的补跑<b>一次</b>（不回放多次）</li>
     * </ul>
     *
     * @return 错过策略
     */
    MisfirePolicy misfire() default MisfirePolicy.SKIP;

    /**
     * 恰一执行开关。false = 退化为本地调度（每实例都跑）——单机开发/演示用；
     * 生产多实例部署保持 true。
     *
     * @return true 启用跨实例恰一（默认）
     */
    boolean guard() default true;
}
