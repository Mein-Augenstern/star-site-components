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


import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 执行器注册表（策略注册）：type() 即路由键，注册时即校验唯一——
 * 路由冲突在启动期暴露，而不是等到第一条命令执行失败才被发现。
 * 首次解析后缓存（执行器集合在运行期不变）。
 * <p>
 * 线程安全性：懒初始化采用"volatile 发布 + 双检锁"，路由表只构建一次后只读；
 * 并发首次访问至多一个线程执行 supplier 求值，其余阻塞等待后直接复用结果。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class ExecutorRegistry {

    /** 执行器集合供应器：仅首次路由解析时调用一次，此后路由表缓存不再回源（如 Spring 注入的 Bean 列表） */
    private final Supplier<List<AsyncCommandExecutor>> supplier;

    /** 路由表缓存：volatile 安全发布——锁内单次写入、此后只读，读线程无锁即可见到完整构建结果 */
    private volatile Map<String, AsyncCommandExecutor> routes;

    /**
     * 构造注册表。
     *
     * @param supplier 执行器集合供应器（只在首次路由解析时调用一次；返回 null 按空集合处理）
     */
    public ExecutorRegistry(Supplier<List<AsyncCommandExecutor>> supplier) {
        this.supplier = supplier;
    }

    /**
     * 按命令类型取执行器。
     *
     * @param cmdType 命令类型（与 AsyncCommandExecutor.type() 精确匹配）
     * @return 对应执行器；未注册返回 null，由调度器按失败重试处理（不抛异常）
     */
    public AsyncCommandExecutor find(String cmdType) {
        return routes().get(cmdType);
    }

    /**
     * 已注册的命令类型集合（启动日志/诊断用）。
     *
     * @return 只读类型集合；首次调用会触发路由解析（存在重复 type 时在此抛出）
     */
    public java.util.Set<String> types() {
        return java.util.Collections.unmodifiableSet(routes().keySet());
    }

    /**
     * 取路由表（懒解析 + 缓存）。
     * 并发设计：第一次判空走无锁快路径；为 null 时加锁二次判空后构建并写入 volatile 字段完成安全发布，
     * 保证只初始化一次、构建结果对其余线程立即可见（半初始化对象不可能被读到）。
     *
     * @return 路由表（cmdType → 执行器，非 null）
     * @throws IllegalStateException 执行器集合存在重复 type——启动期拒绝路由冲突，异常信息含冲突键与次数
     */
    private Map<String, AsyncCommandExecutor> routes() {
        Map<String, AsyncCommandExecutor> local = routes;
        if (local == null) {
            synchronized (this) {
                if (routes == null) {
                    List<AsyncCommandExecutor> list = supplier.get();
                    Map<String, AsyncCommandExecutor> map = (list == null
                            ? List.<AsyncCommandExecutor>of() : list).stream()
                            .collect(Collectors.toMap(AsyncCommandExecutor::type, e -> e));
                    if (map.size() != (list == null ? 0 : list.size())) {
                        List<String> dup = (list == null ? List.<AsyncCommandExecutor>of() : list).stream()
                                .collect(Collectors.groupingBy(AsyncCommandExecutor::type, Collectors.counting()))
                                .entrySet().stream()
                                .filter(e -> e.getValue() > 1)
                                .map(e -> e.getKey() + "×" + e.getValue())
                                .toList();
                        throw new IllegalStateException(
                                MessageFormat.format("AsyncCommandExecutor type 路由冲突（启动期拒绝）: {0}", dup));
                    }
                    routes = map;
                }
                local = routes;
            }
        }
        return local;
    }
}
