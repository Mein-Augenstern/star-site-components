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
package com.xp.starsite.sequence.allocator;

/**
 * 雪花机器位分配器 SPI：为服务实例分配全局唯一（或单机内确定）的 workerId。
 * <p>
 * 为什么需要抽象：workerId 的取值来源由部署形态决定——单机可写死，
 * 多实例必须集中协调，组件把这一步收敛为启动期的唯一扩展点。
 * 内置实现：
 * <ul>
 *   <li>{@link FixedWorkerIdAllocator}——单机形态：配置固定机器位，零外部依赖</li>
 *   <li>{@link MysqlWorkerIdAllocator}——分布式形态：sequence 表 __global__ 池原子租约</li>
 * </ul>
 * 调用约定：启动期调用一次（或极少次重试），无运行期续约语义。
 *
 * @author muyi
 * @date 2026/10/25
 */
public interface WorkerIdAllocator {

    /**
     * 分配一个 workerId，供雪花生成器构造时使用（启动期调用一次）。
     * <p>
     * 实现必须保证同一部署平台内分配结果互不冲突：
     * 单机形态由部署方/编排系统保证，分布式形态由集中存储保证。
     *
     * @return 机器位，取值范围 0 ~ 2^workerIdBits - 1
     * @throws Exception 分配失败：目标存储不可达、sequence 表缺失、分配值超过机器位上限且策略为拒绝等
     */
    long allocate() throws Exception;

    /**
     * 分配器形态描述：启动日志与诊断用，帮助回答"本实例的机器位从哪来"。
     *
     * @return 人类可读的形态描述；默认实现返回实现类简单类名
     */
    default String describe() {
        return getClass().getSimpleName();
    }
}
