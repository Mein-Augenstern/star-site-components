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
package com.xp.starsite.asynccmd;

import java.util.List;

/**
 * 批量执行器：同轮认领到的同类型命令<b>合并成一次调用</b>处理——适用于攒批更高效的场景
 * （批量推送、批量落库、批量调外部接口按次计费等）。
 * <p>
 * 调度器行为：每轮 tick 认领后按 {@link #type()} 分组，组内命令保持 id 升序传入
 * {@link #executeBatch(List)}；实现类自己决定批量策略（可再按 bizKey 二次分组）。
 * <p>
 * <b>原子语义</b>：executeBatch 正常返回 = 本批全部成功（逐条落 SUCCESS）；
 * 抛异常 = 本批<b>全部</b>按该异常走指数退避重试（各自计数，超限各自进 DEAD）。
 * 不支持"批内部分成功"——需要部分成功语义的场景请实现单条
 * {@link AsyncCommandExecutor}，或在批内自行吞掉可容忍的子项失败并在返回前把
 * 失败子项单独落库重试（避免整批反复重放已成功子项造成重复副作用）。
 * <p>
 * 兼容性：实现本接口即自动满足父接口——父接口的 {@link #execute(AsyncCommand)}
 * 已有默认实现转调单元素批（列表化路径统一，行为无分叉）。
 *
 * @author muyi
 * @date 2026/10/25
 */
public interface AsyncCommandBatchExecutor extends AsyncCommandExecutor {

    /**
     * 批量执行同类型命令（组内 id 升序）。
     * 实现约束：副作用需幂等（重试会重放）；对入参列表只读，不缓存引用（调度器不复用列表）。
     *
     * @param cmds 同轮认领的同 {@link #type()} 命令，至少 1 条
     * @throws Exception 任一子项处理失败时抛出；抛出 = 整批重试（见类注释的原子语义）
     */
    void executeBatch(List<AsyncCommand> cmds) throws Exception;

    /** 默认转调单元素批：保持父接口契约成立，单条路径与批量路径行为一致 */
    @Override
    default void execute(AsyncCommand cmd) throws Exception {
        executeBatch(List.of(cmd));
    }
}
