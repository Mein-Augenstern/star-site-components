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
 * 单机形态机器位分配器：分配结果直接采用构造传入的配置值（SequenceConfig 默认 1）。
 * <p>
 * 为什么存在：并非所有部署都需要外部协调——单实例部署、本地开发，
 * 或由外部编排系统（K8s StatefulSet 序号等）保证各实例不同的场景，
 * 用固定值即可零外部依赖地满足 workerId 唯一性。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class FixedWorkerIdAllocator implements WorkerIdAllocator {

    /** 固定返回的机器位 */
    private final long workerId;

    /**
     * 以固定机器位构造分配器。
     *
     * @param workerId 固定返回的机器位（雪花生成器构造时会校验其不超过位宽上限）
     */
    public FixedWorkerIdAllocator(long workerId) {
        this.workerId = workerId;
    }

    /**
     * 返回构造时配置的固定机器位。
     *
     * @return 固定机器位，多次调用结果不变，本实现不抛异常
     */
    @Override
    public long allocate() {
        return workerId;
    }

    /**
     * 分配器形态描述。
     *
     * @return 形如 "standalone/fixed(1)" 的描述，用于启动日志
     */
    @Override
    public String describe() {
        return "standalone/fixed(" + workerId + ")";
    }
}
