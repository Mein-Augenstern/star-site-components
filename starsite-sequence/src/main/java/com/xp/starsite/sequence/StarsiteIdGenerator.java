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
