package com.xp.starsite.asynccmd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 执行器注册表（策略注册）：type() 即路由键，注册时即校验唯一——
 * 路由冲突在启动期暴露，而不是等到第一条命令执行失败才被发现。
 * 首次解析后缓存（执行器集合在运行期不变）。
 *
 * @author muyi
 * @since 2026-10
 */
public class ExecutorRegistry {

    private final Supplier<List<AsyncCommandExecutor>> supplier;
    private volatile Map<String, AsyncCommandExecutor> routes;

    public ExecutorRegistry(Supplier<List<AsyncCommandExecutor>> supplier) {
        this.supplier = supplier;
    }

    /** 按命令类型取执行器（未注册返回 null，由调度器按失败重试处理） */
    public AsyncCommandExecutor find(String cmdType) {
        return routes().get(cmdType);
    }

    /** 已注册的命令类型集合（启动日志/诊断用） */
    public java.util.Set<String> types() {
        return java.util.Collections.unmodifiableSet(routes().keySet());
    }

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
