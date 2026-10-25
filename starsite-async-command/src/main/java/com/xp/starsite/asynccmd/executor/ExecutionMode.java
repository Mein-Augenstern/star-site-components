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

/**
 * 命令执行模式（执行器级语义，{@link AsyncCommandExecutor#mode()} 返回）：
 * 决定「命令什么时候跑」与「跑在哪个线程上」，三种模式的可靠性与吞吐特性各不相同。
 * <ul>
 *   <li>{@link #SCHEDULED}——默认。落库后由调度线程轮询认领<b>内联执行</b>：
 *       严格串行（同轮认领的命令顺序执行）、零额外线程开销；
 *       代价是慢命令会阻塞同轮后续命令——适合轻快任务。</li>
 *   <li>{@link #ASYNC_POOL}——落库后调度线程只认领<b>分发</b>，执行转交线程池：
 *       慢命令不再阻塞调度线程与其他类型；隔离池（实现 {@link PoolIsolatedExecutor}）
 *       时不同类型互不争抢，未隔离时用组件共享池。</li>
 *   <li>{@link #IMMEDIATE}——提交即执行：落库后提交线程立即按 id 认领并直投线程池，
 *       不等轮询周期（默认 5s）。适合对时效敏感的任务；进程在投递前崩溃时，
 *       命令仍是 PENDING，由调度兜底——可靠性不降级。</li>
 * </ul>
 * 三种模式失败语义一致：执行抛异常 = 指数退避重试，超限进 DEAD。
 *
 * @author muyi
 * @date 2026/10/25
 */
public enum ExecutionMode {

    /** 落库 → 调度线程认领 → 调度线程内联执行（串行、默认） */
    SCHEDULED,

    /** 落库 → 调度线程认领 → 转交线程池执行（隔离池或共享池，不阻塞调度） */
    ASYNC_POOL,

    /** 落库 → 提交线程立即认领（按 id）→ 直投线程池执行（不等轮询，调度兜底） */
    IMMEDIATE
}
