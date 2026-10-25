package com.xp.starsite.sequence;

/**
 * 雪花序列参数（纯配置载体，无框架依赖——starter 的 @ConfigurationProperties 继承本类完成绑定）。
 * 参数含义见 README「全量配置」表。
 *
 * @author muyi
 * @since 2026-10
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

    public long getWorkerId() { return workerId; }
    public void setWorkerId(long workerId) { this.workerId = workerId; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public String getInstanceTag() { return instanceTag; }
    public void setInstanceTag(String instanceTag) { this.instanceTag = instanceTag; }
    public long getEpoch() { return epoch; }
    public void setEpoch(long epoch) { this.epoch = epoch; }
    public int getWorkerIdBits() { return workerIdBits; }
    public void setWorkerIdBits(int workerIdBits) { this.workerIdBits = workerIdBits; }
    public int getSequenceBits() { return sequenceBits; }
    public void setSequenceBits(int sequenceBits) { this.sequenceBits = sequenceBits; }
    public long getMaxBackwardMillis() { return maxBackwardMillis; }
    public void setMaxBackwardMillis(long maxBackwardMillis) { this.maxBackwardMillis = maxBackwardMillis; }
    public SnowflakeIdGenerator.BackwardPolicy getBackwardPolicy() { return backwardPolicy; }
    public void setBackwardPolicy(SnowflakeIdGenerator.BackwardPolicy backwardPolicy) { this.backwardPolicy = backwardPolicy; }
    public MysqlWorkerIdAllocator.WraparoundPolicy getWraparoundPolicy() { return wraparoundPolicy; }
    public void setWraparoundPolicy(MysqlWorkerIdAllocator.WraparoundPolicy wraparoundPolicy) { this.wraparoundPolicy = wraparoundPolicy; }
}
