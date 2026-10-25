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
