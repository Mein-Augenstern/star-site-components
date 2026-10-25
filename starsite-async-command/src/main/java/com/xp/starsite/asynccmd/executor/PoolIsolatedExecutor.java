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
package com.xp.starsite.asynccmd.executor;

import java.util.concurrent.Executor;

/**
 * 线程池隔离能力接口：实现它的执行器为<b>本类型</b>绑定独立线程池——
 * 不同任务类型的线程池互不争抢（如"AI 解读"慢任务不占满"消息通知"的线程）。
 * <p>
 * 使用方式：执行器 Bean 内自持应用管理的线程池（依赖注入或自建皆可）并从
 * {@link #pool()} 返回；{@code mode} 为 ASYNC_POOL/IMMEDIATE 时该池生效。
 * 未实现本接口的池化执行器使用组件共享池（参数见
 * {@code starsite.async-command.shared-pool.*}）。
 * <p>
 * 生命周期约定：隔离池归应用所有（组件只使用不关闭）——应用自管关闭时机；
 * 共享池归组件所有（随调度器优雅停机）。
 *
 * @author muyi
 * @date 2026/10/25
 */
public interface PoolIsolatedExecutor {

    /**
     * 本类型的隔离线程池。
     *
     * @return 应用管理的线程池实例（返回后长期稳定复用，不要每次调用新建）
     */
    Executor pool();
}
