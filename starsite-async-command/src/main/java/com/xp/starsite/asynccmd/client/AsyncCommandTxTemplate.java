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
package com.xp.starsite.asynccmd.client;

import com.xp.starsite.asynccmd.model.AsyncCommand;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 事务性提交模板（回调式编程事务）：把"提交命令 + 业务写库"包进同一个数据库事务——
 * 这是异步命令"与业务变更原子落库"立身之本的编程式落地。
 * <p>
 * 为什么不用 @Transactional：组件是库不是应用——注解依赖宿主开启事务代理
 * （proxyTargetClass、exposeProxy 等宿主配置），库内注解不可控；编程式事务
 * 自持 {@link TransactionTemplate}（由 starter 装配注入，缺省回退无事务直连），
 * 事务边界写在代码里对调用方可见，且模板方法内可嵌套任意业务回调。
 * <p>
 * 用法：业务 Service 注入本模板，回调内先做业务变更再 submit——
 * 回调正常返回提交事务（命令与业务同 commit），抛异常整体回滚（命令消失）。
 *
 * @author muyi
 * @date 2026/10/25
 */
public class AsyncCommandTxTemplate {

    /** 日志（slf4j） */
    private static final Logger log = LoggerFactory.getLogger(AsyncCommandTxTemplate.class);

    /** 事务模板（可空：无 DataSource/事务管理器场景回退直连，语义降级为"命令与业务各自提交"） */
    private final TransactionTemplate txTemplate;

    /** 命令客户端（回调内 submit 用） */
    private final AsyncCommandClient client;

    /**
     * 全参构造。
     *
     * @param txTemplate 事务模板（null 表示无事务环境，回调直连执行）
     * @param client     命令客户端
     */
    public AsyncCommandTxTemplate(TransactionTemplate txTemplate, AsyncCommandClient client) {
        this.txTemplate = txTemplate;
        this.client = client;
    }

    /**
     * 事务内运行（有返回值）：回调内先做业务变更再调 {@code submitter} 提交命令，
     * 两者同一事务——回调抛异常业务与命令一起回滚。
     *
     * @param <T>      回调返回类型
     * @param submitter 业务回调（入参为命令客户端，返回业务结果；内部完成业务写库与 submit）
     * @return 回调返回值（事务已提交）
     * @throws RuntimeException 回调抛出的异常原样上抛（事务已回滚，命令未落库）
     */
    public <T> T runInTx(Function<AsyncCommandClient, T> submitter) {
        if (txTemplate == null) {
            // 无事务环境（如本地无 DataSource 测试）：直连运行并记降级，语义为"各自提交"
            log.debug("[starsite-async-command] 无事务模板，命令与业务非原子（降级直连）");
            return submitter.apply(client);
        }
        return txTemplate.execute(status -> submitter.apply(client));
    }

    /**
     * 事务内运行（无返回值变体）。
     *
     * @param submitter 业务回调（内部完成业务写库与 submit）
     * @throws RuntimeException 回调抛出的异常原样上抛（事务已回滚）
     */
    public void runInTx(Consumer<AsyncCommandClient> submitter) {
        runInTx(c -> {
            submitter.accept(c);
            return null;
        });
    }

    /**
     * 事务内提交单条命令（便捷变体，等价于 {@code runInTx(c -> c.submit(...))}）。
     *
     * @param cmdType 命令类型
     * @param bizKey  业务键
     * @param payload 载荷 JSON
     * @return 已落库命令（事务提交后可见）
     */
    public AsyncCommand submitInTx(String cmdType, String bizKey, String payload) {
        return runInTx((Function<AsyncCommandClient, AsyncCommand>) c -> c.submit(cmdType, bizKey, payload));
    }
}
