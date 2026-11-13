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
package com.xp.starsite.idempotent.api;

import java.time.LocalDateTime;

/**
 * 一条幂等记录（idempotent_record 行；result/error 为详情列——本组件无列表查询，
 * 查询均为 uk 点查详情，全列返回）。
 *
 * <p>不可变模型：状态转移经存储层写入后重读（或 memory 形态整行替换），不在模型上开 setter。
 *
 * @author muyi
 * @date 2026/11/12
 */
public class IdempotentRecord {

    /** 主键 id（sequence 组件发号，禁 AUTO_INCREMENT——组件表规范 D2） */
    private final long id;

    /** 键作用域（隔离不同业务的同名键） */
    private final String scope;

    /** 幂等键（业务提供——orderId / 请求头 Idempotency-Key / 复合键拼接） */
    private final String idemKey;

    /** 状态 */
    private final IdempotentStatus status;

    /** 回放载荷（仅 DONE 有值；执行结果经序列化存储——门面路径业务自管字符串，注解路径 JSON） */
    private final String result;

    /** 失败摘要（仅 FAILED 有值；类型+消息截断 500，对齐 async-command last_error 口径） */
    private final String error;

    /** 键记忆窗口边界（认领时刻 + key-ttl；DONE 过期即"键遗忘"，PROCESSING 过期可回收） */
    private final LocalDateTime expireAt;

    /** 终态时刻（进行中为 null） */
    private final LocalDateTime finishedAt;

    /** 认领实例（hostname:pid——对齐 schedule 流水 host 语义） */
    private final String host;

    /** 创建时刻 */
    private final LocalDateTime gmtCreate;

    /** 最后修改时刻（PROCESSING 活跃信号——停滞超阈值判崩溃遗留） */
    private final LocalDateTime gmtModified;

    /**
     * 全参构造（存储层装配与 MyBatis 构造器映射共用——统一包装类型形态：
     * MyBatis 反射按包装类型找构造器，业务侧实参自动装箱，null 防 0）。
     *
     * @param id          主键（可 null——反射映射缺列时视为 0）
     * @param scope       键作用域
     * @param idemKey     幂等键
     * @param status      状态
     * @param result      回放载荷（可 null）
     * @param error       失败摘要（可 null）
     * @param expireAt    键记忆窗口边界
     * @param finishedAt  终态时刻（可 null）
     * @param host        认领实例
     * @param gmtCreate   创建时刻（可 null）
     * @param gmtModified 最后修改时刻（可 null）
     */
    public IdempotentRecord(Long id, String scope, String idemKey, IdempotentStatus status,
                            String result, String error, LocalDateTime expireAt,
                            LocalDateTime finishedAt, String host,
                            LocalDateTime gmtCreate, LocalDateTime gmtModified) {
        this.id = id == null ? 0L : id;
        this.scope = scope;
        this.idemKey = idemKey;
        this.status = status;
        this.result = result;
        this.error = error;
        this.expireAt = expireAt;
        this.finishedAt = finishedAt;
        this.host = host;
        this.gmtCreate = gmtCreate;
        this.gmtModified = gmtModified;
    }

    /**
     * 获取主键。
     *
     * @return 主键 id
     */
    public long getId() {
        return id;
    }

    /**
     * 获取键作用域。
     *
     * @return 作用域（未配置时为空串——单一键空间的缺省段）
     */
    public String getScope() {
        return scope;
    }

    /**
     * 获取幂等键。
     *
     * @return 幂等键
     */
    public String getIdemKey() {
        return idemKey;
    }

    /**
     * 获取状态。
     *
     * @return 状态枚举
     */
    public IdempotentStatus getStatus() {
        return status;
    }

    /**
     * 获取回放载荷。
     *
     * @return 载荷；非 DONE 或 void 执行为 null
     */
    public String getResult() {
        return result;
    }

    /**
     * 获取失败摘要。
     *
     * @return 摘要；非 FAILED 为 null
     */
    public String getError() {
        return error;
    }

    /**
     * 获取键记忆窗口边界。
     *
     * @return 窗口边界时刻
     */
    public LocalDateTime getExpireAt() {
        return expireAt;
    }

    /**
     * 获取终态时刻。
     *
     * @return 终态时刻；进行中为 null
     */
    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    /**
     * 获取认领实例标识。
     *
     * @return hostname:pid
     */
    public String getHost() {
        return host;
    }

    /**
     * 获取创建时刻。
     *
     * @return 创建时刻（memory 形态为注入时钟值；表形态为库时钟）
     */
    public LocalDateTime getGmtCreate() {
        return gmtCreate;
    }

    /**
     * 获取最后修改时刻。
     *
     * @return 最后修改时刻；PROCESSING 停滞判定的活跃信号
     */
    public LocalDateTime getGmtModified() {
        return gmtModified;
    }
}
