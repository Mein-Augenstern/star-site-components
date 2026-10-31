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
package com.xp.starsite.sequence.config;

import com.xp.starsite.sequence.allocator.MysqlWorkerIdAllocator;
import com.xp.starsite.sequence.generator.SnowflakeIdGenerator;

/**
 * 雪花序列参数（纯配置载体，无框架依赖——starter 的 @ConfigurationProperties 继承本类完成绑定）。
 * <p>
 * 参数按形态生效分组（配错形态的参数被静默忽略，不报错——装配层只读本形态所需项）：
 * <ul>
 *   <li><b>standalone</b>：{@code workerId}（固定机器位，多实例下须显式各配不同值——
 *       默认 1 只适用于单进程）；位宽与回拨组全形态生效</li>
 *   <li><b>mysql</b>：{@code serviceName}（审计行 key + upsert 锚）、{@code instanceTag}、
 *       {@code wraparoundPolicy}；{@code workerId} 被租约分配覆盖（忽略）</li>
 *   <li><b>redis</b>：租约 key 前缀等在 starter 层追加（本类不感知）</li>
 * </ul>
 * 位宽铁律：符号位 1 + 时间戳 41 + {@code workerIdBits} + {@code sequenceBits} = 64，
 * 即两段位宽之和不得超过 22——超配在生成器构造期拒绝（fail-fast 而非静默截断）。
 * <p>
 * 线程安全性：普通可变 POJO，未做同步——应在装配/启动期一次性配置完成，
 * 运行期视为只读；运行期并发修改字段不在保证范围。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class SequenceConfig {

    /**
     * standalone 形态的固定机器位（默认 1）。
     * <p>为什么默认 1 仍要警示：standalone 形态的互斥前提是"单进程内唯一"——多实例
     * 共用默认值会立刻跨实例撞号（同 workerId 同毫秒同序列位即重号）。凡部署多于一个
     * 进程就应显式分配（或直接用 mysql/redis 租约形态，机器位由存储端全局分配）。
     */
    private long workerId = 1;

    /**
     * 服务名（mysql 形态审计行 key，默认 "unnamed-service"）。
     * 审计行以 service_name 为唯一键 upsert——同服务重启不膨胀审计行；
     * 分布式部署下同名服务的多实例审计行互相覆盖（保留最近占用者），分配语义不受影响。
     */
    private String serviceName = "unnamed-service";

    /**
     * 实例标识（空串 = 自动生成 "服务@pid@启动戳"）。
     * 写入占用审计行，用于把 workerId 占用追定位到具体进程；多实例同名服务下
     * 自动生成已足够区分，显式指定用于固定标识的运维排查场景。
     */
    private String instanceTag = "";

    /**
     * 起始时间戳毫秒（默认 2026-01-01，即 1767225600000L）。
     * <p>为什么定死后不可再改：epoch 决定时间戳段基数——上线后调整会使新旧 ID 的
     * 时间语义断裂（旧 ID 可能大于新 ID，排序失效；极端回退下与已发 ID 重叠）。
     * 41 位时间戳自 epoch 起可用约 69 年（2^41 ms ≈ 69.7 年）。
     */
    private long epoch = 1767225600000L;

    /**
     * 机器位宽度（默认 10 位 = 最多 1024 个并发实例）。
     * 实例规模接近上限时（如 K8s 弹性扩到千级 Pod）应扩位宽——但位宽调整同样改变
     * ID 结构，须与序列位联动评估（两者之和 ≤ 22）并在低峰停写窗口执行。
     */
    private int workerIdBits = 10;

    /**
     * 序列位宽度（默认 12 位 = 单毫秒单实例最多 4096 个 ID）。
     * 单实例超 409.6 万 ID/秒时会同毫秒溢出（生成器内借下一毫秒，不报错）——
     * 该量级已远超单机业务需求，无需调宽。
     */
    private int sequenceBits = 12;

    /**
     * 时钟回拨容忍上限毫秒（默认 2000ms）。
     * NTP 步进校正通常在几十~几百毫秒量级，2 秒覆盖常规校准；
     * 回拨在此窗口内生成器等待追平（不消耗 ID），超限按 {@code backwardPolicy} 处置。
     */
    private long maxBackwardMillis = 2000;

    /**
     * 时钟回拨超限策略（默认 CLAMP）。
     * CLAMP：钳制到已发最大时间戳并借序列位继续发号（ID 单调性保持，代价是时间戳
     * 段虚增，回拨恢复后自愈）；FAIL：直接抛异常拒绝发号（强一致场景宁停不错）。
     */
    private SnowflakeIdGenerator.BackwardPolicy backwardPolicy = SnowflakeIdGenerator.BackwardPolicy.CLAMP;

    /**
     * 全局池回绕策略（mysql 形态，默认 RESET）。
     * 全局池分配值超过机器位上限（默认 2^10-1）时：RESET 重置全局池重新取号并告警
     * （回绕窗口内与存活旧实例同 id 需"同毫秒同序列"同时命中，概率可忽略但必须可见）；
     * FAIL 直接拒绝启动（保守场景）。实例规模长期接近上限应扩 {@code workerIdBits}
     * 而非依赖回绕。
     */
    private MysqlWorkerIdAllocator.WraparoundPolicy wraparoundPolicy =
            MysqlWorkerIdAllocator.WraparoundPolicy.RESET;

    /** 获取固定机器位。@return 仅 standalone 形态生效的机器位 */
    public long getWorkerId() { return workerId; }
    /** 设置固定机器位。@param workerId 多实例部署必须显式分配不同值（默认 1 仅限单进程） */
    public void setWorkerId(long workerId) { this.workerId = workerId; }
    /** 获取服务名。@return mysql 形态审计行 key */
    public String getServiceName() { return serviceName; }
    /** 设置服务名。@param serviceName 审计行 key（同服务重启 upsert 不膨胀审计行） */
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    /** 获取实例标识。@return 空串表示由分配器自动生成 "服务@pid@启动戳" */
    public String getInstanceTag() { return instanceTag; }
    /** 设置实例标识。@param instanceTag 传空串则自动生成（用于审计定位到具体进程） */
    public void setInstanceTag(String instanceTag) { this.instanceTag = instanceTag; }
    /** 获取起始时间戳。@return epoch 毫秒值 */
    public long getEpoch() { return epoch; }
    /** 设置起始时间戳。@param epoch 须为过去的固定时刻，且上线后不可再改（时间语义断裂） */
    public void setEpoch(long epoch) { this.epoch = epoch; }
    /** 获取机器位宽度。@return 位宽（默认 10 = 1024 实例） */
    public int getWorkerIdBits() { return workerIdBits; }
    /** 设置机器位宽度。@param workerIdBits 与序列位之和不超过 22（构造期校验拒绝） */
    public void setWorkerIdBits(int workerIdBits) { this.workerIdBits = workerIdBits; }
    /** 获取序列位宽度。@return 位宽（默认 12 = 单毫秒 4096 个） */
    public int getSequenceBits() { return sequenceBits; }
    /** 设置序列位宽度。@param sequenceBits 与机器位之和不超过 22（构造期校验拒绝） */
    public void setSequenceBits(int sequenceBits) { this.sequenceBits = sequenceBits; }
    /** 获取时钟回拨容忍上限。@return 毫秒（窗口内等待追平，不消耗 ID） */
    public long getMaxBackwardMillis() { return maxBackwardMillis; }
    /** 设置时钟回拨容忍上限。@param maxBackwardMillis 超限按 backwardPolicy 处置 */
    public void setMaxBackwardMillis(long maxBackwardMillis) { this.maxBackwardMillis = maxBackwardMillis; }
    /** 获取时钟回拨超限策略。@return CLAMP（钳制借序列位）/ FAIL（拒绝发号） */
    public SnowflakeIdGenerator.BackwardPolicy getBackwardPolicy() { return backwardPolicy; }
    /** 设置时钟回拨超限策略。@param backwardPolicy 强一致场景用 FAIL 宁停不错 */
    public void setBackwardPolicy(SnowflakeIdGenerator.BackwardPolicy backwardPolicy) { this.backwardPolicy = backwardPolicy; }
    /** 获取全局池回绕策略。@return RESET（重置重取+告警）/ FAIL（拒绝启动），仅 mysql 形态 */
    public MysqlWorkerIdAllocator.WraparoundPolicy getWraparoundPolicy() { return wraparoundPolicy; }
    /** 设置全局池回绕策略。@param wraparoundPolicy 实例规模接近上限应扩位宽而非依赖回绕 */
    public void setWraparoundPolicy(MysqlWorkerIdAllocator.WraparoundPolicy wraparoundPolicy) { this.wraparoundPolicy = wraparoundPolicy; }
}
