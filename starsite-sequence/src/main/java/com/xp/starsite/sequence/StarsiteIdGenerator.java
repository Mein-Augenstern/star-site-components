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
 * 序列组件门面：业务代码注入本 Bean 调 {@link #next()} / {@link #nextStr()}。
 * 纯 POJO 不绑 Spring——core 模块零框架依赖，starter 负责装配。
 *
 * @author muyi
 * @since 2026-10
 */
public class StarsiteIdGenerator {

    private final SnowflakeIdGenerator delegate;
    private final long workerId;
    private final String allocatorDescription;

    public StarsiteIdGenerator(SnowflakeIdGenerator delegate, long workerId, String allocatorDescription) {
        this.delegate = delegate;
        this.workerId = workerId;
        this.allocatorDescription = allocatorDescription;
    }

    /** 下一个全局唯一 id */
    public long next() {
        return delegate.nextId();
    }

    /** 字符串形态（前端长精度场景） */
    public String nextStr() {
        return delegate.nextIdStr();
    }

    public long getWorkerId() {
        return workerId;
    }

    /** 启动信息（形态 + 机器位，日志打印用） */
    public String describe() {
        return "starsite-sequence: workerId=%d, allocator=%s".formatted(workerId, allocatorDescription);
    }
}
