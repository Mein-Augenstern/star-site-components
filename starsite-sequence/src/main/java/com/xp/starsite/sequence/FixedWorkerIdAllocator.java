package com.xp.starsite.sequence;

/**
 * 单机形态机器位分配器：直接采用配置值（默认 1）。
 * 适用于：单实例部署、本地开发、或由外部编排系统（K8s StatefulSet 序号等）保证各实例不同的场景。
 *
 * @author muyi
 * @since 2026-10
 */
public class FixedWorkerIdAllocator implements WorkerIdAllocator {

    private final long workerId;

    public FixedWorkerIdAllocator(long workerId) {
        this.workerId = workerId;
    }

    @Override
    public long allocate() {
        return workerId;
    }

    @Override
    public String describe() {
        return "standalone/fixed(" + workerId + ")";
    }
}
