package com.xp.starsite.asynccmd;

import java.time.LocalDateTime;

/**
 * 异步命令核心模型：一条待执行命令 = 表/内存里的一行。
 * 状态机 PENDING → RUNNING → SUCCESS / PENDING(重试) / DEAD(死信)。
 *
 * @author muyi
 * @since 2026-10
 */
public class AsyncCommand {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String DEAD = "DEAD";

    private Long id;
    /** 命令类型 = 执行器路由键 */
    private String cmdType;
    /** 业务键（幂等/追踪） */
    private String bizKey;
    /** 载荷 JSON */
    private String payload;
    private String status;
    private Integer maxRetry;
    private Integer retryCount;
    /** 到期时间（延迟队列锚点） */
    private LocalDateTime nextRunAt;
    private String lastError;
    /** 提交方服务名 */
    private String owner;
    /** 调度认领令牌（认领瞬间生成，处理完成后清空） */
    private String claimToken;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCmdType() { return cmdType; }
    public void setCmdType(String cmdType) { this.cmdType = cmdType; }
    public String getBizKey() { return bizKey; }
    public void setBizKey(String bizKey) { this.bizKey = bizKey; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getMaxRetry() { return maxRetry; }
    public void setMaxRetry(Integer maxRetry) { this.maxRetry = maxRetry; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public LocalDateTime getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(LocalDateTime nextRunAt) { this.nextRunAt = nextRunAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getClaimToken() { return claimToken; }
    public void setClaimToken(String claimToken) { this.claimToken = claimToken; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
