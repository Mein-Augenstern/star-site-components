package com.xp.starsite.asynccmd;

/**
 * 异步命令参数（纯配置载体，无框架依赖——starter 的 @ConfigurationProperties 继承本类完成绑定）。
 * 参数含义见 README「全量配置」表。
 *
 * @author muyi
 * @since 2026-10
 */
public class AsyncCommandConfig {

    /** 调度轮询间隔毫秒 */
    private long pollIntervalMs = 5000;

    /** 单轮认领批量 */
    private int batchSize = 10;

    /** RUNNING 悬挂判定秒数（执行器进程死亡残留，超时回收回 PENDING） */
    private int staleAfterSeconds = 300;

    /** 默认最大重试次数（超限进 DEAD） */
    private int defaultMaxRetry = 5;

    /** 退避基数秒（第 n 次重试等待 base * 2^n） */
    private long backoffBaseSeconds = 60;

    /** 退避封顶秒 */
    private long backoffMaxSeconds = 3600;

    /** 提交方标识（starter 默认取 spring.application.name 填充） */
    private String owner = "";

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getStaleAfterSeconds() { return staleAfterSeconds; }
    public void setStaleAfterSeconds(int staleAfterSeconds) { this.staleAfterSeconds = staleAfterSeconds; }
    public int getDefaultMaxRetry() { return defaultMaxRetry; }
    public void setDefaultMaxRetry(int defaultMaxRetry) { this.defaultMaxRetry = defaultMaxRetry; }
    public long getBackoffBaseSeconds() { return backoffBaseSeconds; }
    public void setBackoffBaseSeconds(long backoffBaseSeconds) { this.backoffBaseSeconds = backoffBaseSeconds; }
    public long getBackoffMaxSeconds() { return backoffMaxSeconds; }
    public void setBackoffMaxSeconds(long backoffMaxSeconds) { this.backoffMaxSeconds = backoffMaxSeconds; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
}
