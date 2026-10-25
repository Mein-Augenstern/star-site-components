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
package com.xp.starsite.sequence;

/**
 * 雪花序列参数（纯配置载体，无框架依赖——starter 的 @ConfigurationProperties 继承本类完成绑定）。
 * 参数含义见 README「全量配置」表。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class SequenceConfig {

    /** standalone 形态的固定机器位 */
    private long workerId = 1;

    /** 服务名（mysql 形态审计行 key） */
    private String serviceName = "unnamed-service";

    /** 实例标识（空串 = 自动生成 服务@pid@启动戳） */
    private String instanceTag = "";

    /** 起始时间戳（默认 2026-01-01，41 位时间戳可用约 69 年） */
    private long epoch = 1767225600000L;

    /** 机器位宽度（默认 10 位 = 1024 实例） */
    private int workerIdBits = 10;

    /** 序列位宽度（默认 12 位 = 单毫秒 4096 个） */
    private int sequenceBits = 12;

    /** 时钟回拨容忍上限毫秒（超限按策略处理） */
    private long maxBackwardMillis = 2000;

    /** 时钟回拨策略：CLAMP 钳制借序列位（默认）/ FAIL 超限拒绝 */
    private SnowflakeIdGenerator.BackwardPolicy backwardPolicy = SnowflakeIdGenerator.BackwardPolicy.CLAMP;

    /** 回绕策略（mysql 形态）：RESET 重置全局池（默认）/ FAIL 拒绝 */
    private MysqlWorkerIdAllocator.WraparoundPolicy wraparoundPolicy =
            MysqlWorkerIdAllocator.WraparoundPolicy.RESET;

    /** 获取固定机器位（仅 standalone 形态生效）。 */
    public long getWorkerId() { return workerId; }
    /** 设置固定机器位（仅 standalone 形态生效）。 */
    public void setWorkerId(long workerId) { this.workerId = workerId; }
    /** 获取服务名（mysql 形态审计行 key）。 */
    public String getServiceName() { return serviceName; }
    /** 设置服务名（mysql 形态审计行 key）。 */
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    /** 获取实例标识（空串 = 自动生成 服务@pid@启动戳）。 */
    public String getInstanceTag() { return instanceTag; }
    /** 设置实例标识（传空串则自动生成）。 */
    public void setInstanceTag(String instanceTag) { this.instanceTag = instanceTag; }
    /** 获取起始时间戳毫秒。 */
    public long getEpoch() { return epoch; }
    /** 设置起始时间戳毫秒（须为过去的固定时刻）。 */
    public void setEpoch(long epoch) { this.epoch = epoch; }
    /** 获取机器位宽度（位）。 */
    public int getWorkerIdBits() { return workerIdBits; }
    /** 设置机器位宽度（位），与序列位之和不超过 22。 */
    public void setWorkerIdBits(int workerIdBits) { this.workerIdBits = workerIdBits; }
    /** 获取序列位宽度（位）。 */
    public int getSequenceBits() { return sequenceBits; }
    /** 设置序列位宽度（位），与机器位之和不超过 22。 */
    public void setSequenceBits(int sequenceBits) { this.sequenceBits = sequenceBits; }
    /** 获取时钟回拨容忍上限毫秒。 */
    public long getMaxBackwardMillis() { return maxBackwardMillis; }
    /** 设置时钟回拨容忍上限毫秒（超限按回拨策略处置）。 */
    public void setMaxBackwardMillis(long maxBackwardMillis) { this.maxBackwardMillis = maxBackwardMillis; }
    /** 获取时钟回拨超限策略。 */
    public SnowflakeIdGenerator.BackwardPolicy getBackwardPolicy() { return backwardPolicy; }
    /** 设置时钟回拨超限策略（CLAMP 钳制 / FAIL 拒绝）。 */
    public void setBackwardPolicy(SnowflakeIdGenerator.BackwardPolicy backwardPolicy) { this.backwardPolicy = backwardPolicy; }
    /** 获取全局池回绕策略（mysql 形态）。 */
    public MysqlWorkerIdAllocator.WraparoundPolicy getWraparoundPolicy() { return wraparoundPolicy; }
    /** 设置全局池回绕策略（RESET 重置重取 / FAIL 拒绝）。 */
    public void setWraparoundPolicy(MysqlWorkerIdAllocator.WraparoundPolicy wraparoundPolicy) { this.wraparoundPolicy = wraparoundPolicy; }
}
