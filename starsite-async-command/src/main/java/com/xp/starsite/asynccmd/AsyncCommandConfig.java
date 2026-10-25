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
package com.xp.starsite.asynccmd;

/**
 * 异步命令参数（纯配置载体，无框架依赖——starter 的 @ConfigurationProperties 继承本类完成绑定）。
 * 参数含义见 README「全量配置」表。
 * <p>
 * 线程安全性：普通可变 POJO，未做同步——应在装配/启动期一次性配置完成，
 * 运行期视为只读；运行期并发修改字段不在保证范围。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class AsyncCommandConfig {

    /** 调度轮询间隔毫秒。误配 0/负值会被调度器按 MIN_POLL_INTERVAL_MS 下限钳位——防打爆数据库。 */
    private long pollIntervalMs = 5000;

    /** 单轮认领批量（条）。过大单轮执行时间变长、悬挂误判风险升高；过小吞吐不足。 */
    private int batchSize = 10;

    /** RUNNING 悬挂判定秒数（执行器进程死亡残留，超时回收回 PENDING）。必须大于单条命令最长执行时长，否则活命令被误回收重复执行。 */
    private int staleAfterSeconds = 300;

    /** 默认最大重试次数（超限进 DEAD）。命令未单独设置 maxRetry 时使用。 */
    private int defaultMaxRetry = 5;

    /** 退避基数秒（第 n 次重试等待 base * 2^n）。 */
    private long backoffBaseSeconds = 60;

    /** 退避封顶秒（指数增长上界，防止长退避把命令"冻住"过久）。 */
    private long backoffMaxSeconds = 3600;

    /** 提交方标识（starter 默认取 spring.application.name 填充；多服务共表时区分来源）。 */
    private String owner = "";

    /**
     * 获取调度轮询间隔。
     *
     * @return 轮询间隔（毫秒）；实际生效值会被调度器钳位到 MIN_POLL_INTERVAL_MS 下限
     */
    public long getPollIntervalMs() { return pollIntervalMs; }

    /**
     * 设置调度轮询间隔。
     *
     * @param pollIntervalMs 轮询间隔（毫秒），小于下限时按 MIN_POLL_INTERVAL_MS 生效
     */
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    /**
     * 获取单轮认领批量。
     *
     * @return 单轮认领批量（条）
     */
    public int getBatchSize() { return batchSize; }

    /**
     * 设置单轮认领批量。
     *
     * @param batchSize 单轮认领批量（条，正数）
     */
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    /**
     * 获取 RUNNING 悬挂判定阈值。
     *
     * @return 悬挂判定阈值（秒）
     */
    public int getStaleAfterSeconds() { return staleAfterSeconds; }

    /**
     * 设置 RUNNING 悬挂判定阈值。
     *
     * @param staleAfterSeconds 悬挂判定阈值（秒），须大于单条命令最长执行时长
     */
    public void setStaleAfterSeconds(int staleAfterSeconds) { this.staleAfterSeconds = staleAfterSeconds; }

    /**
     * 获取默认最大重试次数。
     *
     * @return 默认最大重试次数（命令未单独设置 maxRetry 时生效）
     */
    public int getDefaultMaxRetry() { return defaultMaxRetry; }

    /**
     * 设置默认最大重试次数。
     *
     * @param defaultMaxRetry 默认最大重试次数（重试达到该值仍失败则进 DEAD）
     */
    public void setDefaultMaxRetry(int defaultMaxRetry) { this.defaultMaxRetry = defaultMaxRetry; }

    /**
     * 获取退避基数。
     *
     * @return 退避基数（秒），第 n 次重试等待 base * 2^n
     */
    public long getBackoffBaseSeconds() { return backoffBaseSeconds; }

    /**
     * 设置退避基数。
     *
     * @param backoffBaseSeconds 退避基数（秒，非负）
     */
    public void setBackoffBaseSeconds(long backoffBaseSeconds) { this.backoffBaseSeconds = backoffBaseSeconds; }

    /**
     * 获取退避封顶。
     *
     * @return 退避封顶（秒），单次重试等待的最大值
     */
    public long getBackoffMaxSeconds() { return backoffMaxSeconds; }

    /**
     * 设置退避封顶。
     *
     * @param backoffMaxSeconds 退避封顶（秒），应不小于退避基数
     */
    public void setBackoffMaxSeconds(long backoffMaxSeconds) { this.backoffMaxSeconds = backoffMaxSeconds; }

    /**
     * 获取提交方标识。
     *
     * @return 提交方服务名；未设置为空串
     */
    public String getOwner() { return owner; }

    /**
     * 设置提交方标识。
     *
     * @param owner 提交方服务名（写入命令的 owner 字段，用于多服务共表时区分来源）
     */
    public void setOwner(String owner) { this.owner = owner; }
}
