package com.xp.starsite.sequence;

/**
 * 雪花机器位分配器：为服务实例分配全局唯一（或单机内确定）的 workerId。
 * <ul>
 *   <li>{@link FixedWorkerIdAllocator}——单机形态：配置固定机器位，零外部依赖</li>
 *   <li>{@link MysqlWorkerIdAllocator}——分布式形态：sequence 表 __global__ 池原子租约</li>
 * </ul>
 *
 * @author muyi
 * @since 2026-10
 */
public interface WorkerIdAllocator {

    /**
     * 分配 workerId（启动期调用一次）。
     *
     * @return 机器位（0 ~ 2^workerIdBits - 1）
     * @throws Exception 分配失败（表缺失/数据库不可达/超上限按策略拒绝）
     */
    long allocate() throws Exception;

    /** 分配器形态描述（启动日志与诊断用） */
    default String describe() {
        return getClass().getSimpleName();
    }
}
