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
package com.xp.starsite.asynccmd.model;

import java.time.LocalDateTime;

/**
 * 异步命令核心模型：一条待执行命令 = 表/内存里的一行。
 * 状态机 PENDING → RUNNING → SUCCESS / PENDING(重试) / DEAD(死信)，各状态的入口/出口语义见状态常量注释。
 * <p>
 * 为什么存在：业务方在自己的事务内提交命令（与业务变更原子落库），调度器异步认领并路由执行，
 * 以"表 + 状态机"换取可靠执行、失败重试与死信兜底。
 * <p>
 * 线程安全性：可变 POJO，非线程安全；跨线程传递依赖存储实现的快照拷贝（如内存存储的 copy），
 * 不要让业务线程与调度线程共享同一实例并发读写。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class AsyncCommand {

    /** 状态-初始态（兼容常量，指向 {@link CommandStatus#PENDING} 的存储码） */
    public static final String PENDING = CommandStatus.PENDING.getCode();

    /** 状态-执行中（兼容常量，指向 {@link CommandStatus#RUNNING} 的存储码） */
    public static final String RUNNING = CommandStatus.RUNNING.getCode();

    /** 状态-终态：成功（兼容常量，指向 {@link CommandStatus#SUCCESS} 的存储码） */
    public static final String SUCCESS = CommandStatus.SUCCESS.getCode();

    /** 状态-终态：死信（兼容常量，指向 {@link CommandStatus#DEAD} 的存储码） */
    public static final String DEAD = CommandStatus.DEAD.getCode();

    /** 主键 id（由存储分配，insert 后回填；未落库时为 null） */
    private Long id;
    /** 命令类型 = 执行器路由键 */
    private String cmdType;
    /** 业务键（幂等/追踪） */
    private String bizKey;
    /** 载荷 JSON */
    private String payload;
    /** 当前状态（枚举，迁移规则见 {@link CommandStatus#canAutoTransitionTo}） */
    private CommandStatus status;
    /** 最大重试次数上限（失败重试达到该值仍失败则进 DEAD；为 null 时按配置默认值处理） */
    private Integer maxRetry;
    /** 已重试次数（0 起算，每失败一次加 1） */
    private Integer retryCount;
    /** 到期时间（延迟队列锚点） */
    private LocalDateTime nextRunAt;
    /** 最后一次失败原因（超长会截断存储；重试与进 DEAD 时回写） */
    private String lastError;
    /** 提交方服务名 */
    private String owner;
    /** 调度认领令牌（认领瞬间生成，处理完成后清空） */
    private String claimToken;
    /** 创建时间（由存储维护） */
    private LocalDateTime createdAt;
    /** 最后更新时间（由存储维护；悬挂回收以它判定 RUNNING 是否超时） */
    private LocalDateTime updatedAt;

    /**
     * 获取主键 id。
     *
     * @return 主键 id；未落库（存储尚未分配）时为 null
     */
    public Long getId() { return id; }

    /**
     * 设置主键 id。
     *
     * @param id 主键 id（一般由存储 insert 时回填，业务方无需手工设置）
     */
    public void setId(Long id) { this.id = id; }

    /**
     * 获取命令类型。
     *
     * @return 命令类型（执行器路由键）
     */
    public String getCmdType() { return cmdType; }

    /**
     * 设置命令类型。
     *
     * @param cmdType 命令类型（执行器路由键，须与某个 AsyncCommandExecutor.type() 一致，否则按无执行器重试）
     */
    public void setCmdType(String cmdType) { this.cmdType = cmdType; }

    /**
     * 获取业务键。
     *
     * @return 业务键（幂等去重/追踪用；提交侧已把 null 归一为空串）
     */
    public String getBizKey() { return bizKey; }

    /**
     * 设置业务键。
     *
     * @param bizKey 业务键（幂等去重/追踪用；null 会被提交侧归一为空串）
     */
    public void setBizKey(String bizKey) { this.bizKey = bizKey; }

    /**
     * 获取载荷 JSON。
     *
     * @return 载荷 JSON 字符串（结构由执行器约定并解析；未携带载荷时为 null）
     */
    public String getPayload() { return payload; }

    /**
     * 设置载荷 JSON。
     *
     * @param payload 载荷 JSON 字符串（结构由执行器约定；允许 null）
     */
    public void setPayload(String payload) { this.payload = payload; }

    /**
     * 获取当前状态（枚举形态，迁移判断用）。
     *
     * @return 当前状态枚举
     */
    public CommandStatus getStatusEnum() { return status; }

    /**
     * 设置当前状态（枚举形态——迁移前建议经
     * {@link CommandStatus#canAutoTransitionTo} 守卫）。
     *
     * @param status 目标状态枚举
     */
    public void setStatusEnum(CommandStatus status) { this.status = status; }

    /**
     * 获取当前状态的存储码（String 兼容形态——行映射/JSON 序列化用；
     * 与 {@code getStatusEnum().getCode()} 等价）。
     *
     * @return 存储码（PENDING/RUNNING/SUCCESS/DEAD）
     */
    public String getStatus() { return status == null ? null : status.getCode(); }

    /**
     * 按存储码设置状态（String 兼容形态——行映射/外部数据装载用；
     * 未知码 fail-fast 抛 IllegalArgumentException）。
     *
     * @param code 状态存储码
     * @throws IllegalArgumentException 未知状态码
     */
    public void setStatus(String code) { this.status = CommandStatus.fromCode(code); }

    /**
     * 获取最大重试次数上限。
     *
     * @return 最大重试次数上限；为 null 时按配置 defaultMaxRetry 处理
     */
    public Integer getMaxRetry() { return maxRetry; }

    /**
     * 设置最大重试次数上限。
     *
     * @param maxRetry 最大重试次数上限（重试达到该值仍失败则进 DEAD；null 表示沿用配置默认值）
     */
    public void setMaxRetry(Integer maxRetry) { this.maxRetry = maxRetry; }

    /**
     * 获取已重试次数。
     *
     * @return 已重试次数（0 起算，每失败一次加 1）
     */
    public Integer getRetryCount() { return retryCount; }

    /**
     * 设置已重试次数。
     *
     * @param retryCount 已重试次数（0 起算）
     */
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }

    /**
     * 获取到期时间。
     *
     * @return 到期时间（延迟队列锚点，到期前不会被认领）；为 null 视为立即到期
     */
    public LocalDateTime getNextRunAt() { return nextRunAt; }

    /**
     * 设置到期时间。
     *
     * @param nextRunAt 到期时间；传当前时间表示立即执行，传未来时间表示延迟执行
     */
    public void setNextRunAt(LocalDateTime nextRunAt) { this.nextRunAt = nextRunAt; }

    /**
     * 获取最后一次失败原因。
     *
     * @return 最后一次失败原因（截断存储）；未失败过为 null
     */
    public String getLastError() { return lastError; }

    /**
     * 设置最后一次失败原因。
     *
     * @param lastError 最后一次失败原因（存储侧会按列宽截断，超长由调用方保证不越界）
     */
    public void setLastError(String lastError) { this.lastError = lastError; }

    /**
     * 获取提交方服务名。
     *
     * @return 提交方服务名（多服务共表时区分来源）；未设置为空串或 null
     */
    public String getOwner() { return owner; }

    /**
     * 设置提交方服务名。
     *
     * @param owner 提交方服务名（starter 默认取 spring.application.name 填充）
     */
    public void setOwner(String owner) { this.owner = owner; }

    /**
     * 获取调度认领令牌。
     *
     * @return 认领令牌；未被认领或已处理完成（令牌清空）时为 null
     */
    public String getClaimToken() { return claimToken; }

    /**
     * 设置调度认领令牌。
     *
     * @param claimToken 认领令牌（存储在 claim 时写入，updateState 终态回写时清空）
     */
    public void setClaimToken(String claimToken) { this.claimToken = claimToken; }

    /**
     * 获取创建时间。
     *
     * @return 创建时间；未落库时为 null
     */
    public LocalDateTime getCreatedAt() { return createdAt; }

    /**
     * 设置创建时间。
     *
     * @param createdAt 创建时间（由存储维护，insert 时写入）
     */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    /**
     * 获取最后更新时间。
     *
     * @return 最后更新时间（RUNNING 悬挂判定的锚点）；未落库时为 null
     */
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    /**
     * 设置最后更新时间。
     *
     * @param updatedAt 最后更新时间（由存储在每次写入时刷新）
     */
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
