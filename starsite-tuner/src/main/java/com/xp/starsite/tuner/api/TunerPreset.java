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
 * 参数预案（命名参数集——tuner_preset 行）：core/max/keepAlive + queueCapacity（V2）。
 * 语义（金融信贷方法论）：预案 = "还款日扩容档 / 日常档 / 日终批档"——提前配置、
 * 按窗口切换、一键回滚。
 *
 * <p><b>契约</b>：
 * <ul>
 *   <li>core/max/keepAlive 对<b>有界队列</b>池全部有效；无界 LinkedBlockingQueue 池
 *       的 max 不参与扩容（loudly 写明：有界队列池才值得治理）</li>
 *   <li>{@code queueCapacity}（V2）：null = 不管理队列容量；非 null 时受管池的队列
 *       必须是 {@link com.xp.starsite.tuner.queue.ResizableCapacityLinkedBlockingQueue}
 *       （构造池时用 {@code TunerQueues.resizable(n)}），否则 WARN 跳过</li>
 * </ul>
 *
 * @author muyi
 * @date 2026/11/14
 */
public class TunerPreset {

    /** 预案名（uk；业务语义命名——"repayday"/"daily"/"batch-window"） */
    private final String presetName;

    /** 核心线程数 */
    private final int coreSize;

    /** 最大线程数 */
    private final int maxSize;

    /** 空闲保活（秒） */
    private final int keepAliveSeconds;

    /** 队列容量（null=不管理队列容量；非 null 要求池队列为可变容量队列） */
    private final Integer queueCapacity;

    /**
     * V1 形态构造（不管理队列容量——兼容既有调用）。
     *
     * @param presetName       预案名
     * @param coreSize         核心线程数
     * @param maxSize          最大线程数（>= coreSize）
     * @param keepAliveSeconds 空闲保活秒
     */
    public TunerPreset(String presetName, int coreSize, int maxSize, int keepAliveSeconds) {
        this(presetName, coreSize, maxSize, keepAliveSeconds, null);
    }

    /**
     * V2 形态构造（含队列容量）。
     *
     * @param presetName       预案名
     * @param coreSize         核心线程数
     * @param maxSize          最大线程数（>= coreSize）
     * @param keepAliveSeconds 空闲保活秒
     * @param queueCapacity    队列容量（null=不管理；非 null 要求池队列为可变容量队列）
     */
    public TunerPreset(String presetName, int coreSize, int maxSize, int keepAliveSeconds,
                       Integer queueCapacity) {
        this.presetName = presetName;
        this.coreSize = coreSize;
        this.maxSize = maxSize;
        this.keepAliveSeconds = keepAliveSeconds;
        this.queueCapacity = queueCapacity;
    }

    /**
     * MyBatis 映射形态构造（包装类型——反射按包装类型找构造器，null 防 0/防不管理语义）。
     *
     * @param presetName       预案名
     * @param coreSize         核心线程数（可 null——视为 0）
     * @param maxSize          最大线程数（可 null）
     * @param keepAliveSeconds 保活秒（可 null）
     * @param queueCapacity    队列容量（可 null=不管理）
     */
    public TunerPreset(String presetName, Long coreSize, Long maxSize, Long keepAliveSeconds,
                       Long queueCapacity) {
        this(presetName,
                coreSize == null ? 0 : coreSize.intValue(),
                maxSize == null ? 0 : maxSize.intValue(),
                keepAliveSeconds == null ? 0 : keepAliveSeconds.intValue(),
                queueCapacity == null ? null : queueCapacity.intValue());
    }

    /**
     * 校验预案合法性（写入期 fail-fast：core 正数、max>=core、keepAlive>=0、
     * queueCapacity 为正或 null）。
     *
     * @throws IllegalArgumentException 参数非法
     */
    public void validate() {
        if (presetName == null || presetName.trim().isEmpty()) {
            throw new IllegalArgumentException("预案名不可为空");
        }
        if (coreSize <= 0) {
            throw new IllegalArgumentException("核心线程数必须为正: " + presetName);
        }
        if (maxSize < coreSize) {
            throw new IllegalArgumentException(
                    "最大线程数 >= 核心线程数 required: max=" + maxSize + ", core=" + coreSize);
        }
        if (keepAliveSeconds < 0) {
            throw new IllegalArgumentException("保活秒不可为负: " + presetName);
        }
        if (queueCapacity != null && queueCapacity <= 0) {
            throw new IllegalArgumentException("队列容量必须为正或 null（不管理）: " + presetName);
        }
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
     * 获取核心线程数。
     *
     * @return 核心线程数
     */
    public int getCoreSize() {
        return coreSize;
    }

    /**
     * 获取最大线程数。
     *
     * @return 最大线程数
     */
    public int getMaxSize() {
        return maxSize;
    }

    /**
     * 获取空闲保活秒。
     *
     * @return 保活秒
     */
    public int getKeepAliveSeconds() {
        return keepAliveSeconds;
    }

    /**
     * 获取队列容量。
     *
     * @return 队列容量；null=不管理队列容量
     */
    public Integer getQueueCapacity() {
        return queueCapacity;
    }
}
