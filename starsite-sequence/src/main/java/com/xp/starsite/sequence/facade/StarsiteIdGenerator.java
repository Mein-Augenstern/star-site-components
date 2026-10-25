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
package com.xp.starsite.sequence.facade;

import com.xp.starsite.sequence.allocator.WorkerIdAllocator;
import com.xp.starsite.sequence.generator.SnowflakeIdGenerator;

/**
 * 序列组件门面：业务代码注入本 Bean 调 {@link #next()} / {@link #nextStr()} 获取全局唯一 id。
 * <p>
 * 为什么存在：core 模块零框架依赖（纯 POJO 不绑 Spring），由 starter 负责装配；
 * 门面持有配置完成的 {@link SnowflakeIdGenerator} 与启动期的分配元信息，
 * 让业务方只面对"发号"这一个入口，不感知位宽/回拨策略等底层细节。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class StarsiteIdGenerator {

    /** 实际执行发号的雪花生成器（含时钟回拨处理），构造注入后不可变 */
    private final SnowflakeIdGenerator delegate;

    /** 启动期由 {@link WorkerIdAllocator} 分配到的机器位，仅用于诊断输出 */
    private final long workerId;

    /** 机器位分配器形态描述（如 standalone/fixed、mysql-lease），用于 {@link #describe()} 输出 */
    private final String allocatorDescription;

    /**
     * 以底层生成器与分配元信息构造门面。
     *
     * @param delegate             已完成参数校验的雪花生成器实例，由装配方创建
     * @param workerId             启动期分配到的机器位（0 ~ 2^workerIdBits - 1）
     * @param allocatorDescription 分配器形态描述，随 {@link #describe()} 打入启动日志
     */
    public StarsiteIdGenerator(SnowflakeIdGenerator delegate, long workerId, String allocatorDescription) {
        this.delegate = delegate;
        this.workerId = workerId;
        this.allocatorDescription = allocatorDescription;
    }

    /**
     * 生成下一个全局唯一 id。
     *
     * @return 新发出的 64 位雪花 id（同一生成器内严格递增，跨实例全局唯一）
     * @throws ClockBackwardException 时钟回拨超过容忍上限且策略为 FAIL 时，拒绝发号向上抛出
     */
    public long next() {
        return delegate.nextId();
    }

    /**
     * 生成下一个全局唯一 id 的字符串形态。
     * <p>
     * 前端 JavaScript Number 仅有 53 位精度，64 位 long 直传会丢精度，
     * 前后端交互场景应统一使用本方法的字符串形态。
     *
     * @return 无符号十进制字符串形态的雪花 id
     * @throws ClockBackwardException 抛出条件同 {@link #next()}
     */
    public String nextStr() {
        return delegate.nextIdStr();
    }

    /**
     * 获取启动期分配到的机器位。
     *
     * @return 机器位（0 ~ 2^workerIdBits - 1）
     */
    public long getWorkerId() {
        return workerId;
    }

    /**
     * 启动信息描述（形态 + 机器位），用于启动日志打印与运维排查。
     *
     * @return 形如 "starsite-sequence: workerId=1, allocator=standalone/fixed(1)" 的描述串
     */
    public String describe() {
        return "starsite-sequence: workerId=%d, allocator=%s".formatted(workerId, allocatorDescription);
    }
}
