package com.xp.starsite.asynccmd;

import com.xp.starsite.asynccmd.store.AsyncCommandStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 异步命令提交入口：在业务事务内调用 submit，命令与业务变更同事务原子落库
 * （表形态的立身之本），提交方只管写，调度器负责可靠执行与重试。
 *
 * @author muyi
 * @since 2026-10
 */
public class AsyncCommandClient {

    private static final Logger log = LoggerFactory.getLogger(AsyncCommandClient.class);

    private final AsyncCommandStore store;
    private final AsyncCommandConfig config;
    private final TimeSource time;

    public AsyncCommandClient(AsyncCommandStore store, AsyncCommandConfig config) {
        this(store, config, TimeSource.system());
    }

    public AsyncCommandClient(AsyncCommandStore store, AsyncCommandConfig config, TimeSource time) {
        this.store = store;
        this.config = config;
        this.time = time;
    }

    /** 立即执行 */
    public AsyncCommand submit(String cmdType, String bizKey, String payloadJson) {
        return submitAt(cmdType, bizKey, payloadJson, time.now());
    }

    /** 延迟执行（延迟队列场景，如"30 分钟未支付取消订单"） */
    public AsyncCommand submitAt(String cmdType, String bizKey, String payloadJson, LocalDateTime runAt) {
        AsyncCommand cmd = new AsyncCommand();
        cmd.setCmdType(cmdType);
        cmd.setBizKey(bizKey == null ? "" : bizKey);
        cmd.setPayload(payloadJson);
        cmd.setStatus(AsyncCommand.PENDING);
        cmd.setMaxRetry(config.getDefaultMaxRetry());
        cmd.setRetryCount(0);
        cmd.setNextRunAt(runAt);
        cmd.setOwner(config.getOwner());
        store.insert(cmd);
        log.info("async cmd submitted: type={}, key={}, id={}, runAt={}", cmdType, bizKey, cmd.getId(), runAt);
        return cmd;
    }

    /** 幂等提交：同 type+bizKey 已有未完成命令时跳过（防重复触发，如定时任务反复提交） */
    public AsyncCommand submitIdempotent(String cmdType, String bizKey, String payloadJson) {
        if (store.countUnfinished(cmdType, bizKey == null ? "" : bizKey) > 0) {
            return null;
        }
        return submit(cmdType, bizKey, payloadJson);
    }

    /** 按 id 取命令（执行器回显/排查用，不存在返回 null） */
    public AsyncCommand findById(long id) {
        return store.findById(id);
    }

    /** 处理成功回写（清令牌 + SUCCESS） */
    public void markSuccess(AsyncCommand cmd) {
        cmd.setStatus(AsyncCommand.SUCCESS);
        cmd.setClaimToken(null);
        store.updateState(cmd);
        log.debug("async cmd done: id={}, type={}", cmd.getId(), cmd.getCmdType());
    }

    /**
     * 失败重试：指数退避（base·2^n，封顶 max）；超限置 DEAD（死信）。
     */
    public void markRetryOrFail(AsyncCommand cmd, Exception cause) {
        int retry = cmd.getRetryCount() == null ? 0 : cmd.getRetryCount();
        String msg = cause == null ? "" : String.valueOf(cause.getMessage());
        cmd.setClaimToken(null);
        if (retry >= (cmd.getMaxRetry() == null ? config.getDefaultMaxRetry() : cmd.getMaxRetry())) {
            cmd.setStatus(AsyncCommand.DEAD);
            cmd.setLastError(truncate(msg));
            store.updateState(cmd);
            log.error("async cmd DEAD: id={}, type={}, lastError={}", cmd.getId(), cmd.getCmdType(), msg);
            return;
        }
        cmd.setRetryCount(retry + 1);
        long backoffSeconds = Math.min(
                config.getBackoffBaseSeconds() * (1L << Math.min(retry, 16)),
                config.getBackoffMaxSeconds());
        cmd.setStatus(AsyncCommand.PENDING);
        cmd.setNextRunAt(time.now().plusSeconds(backoffSeconds));
        cmd.setLastError(truncate(msg));
        store.updateState(cmd);
        log.warn("async cmd retry #{}: id={}, type={}, backoff={}s, err={}",
                retry + 1, cmd.getId(), cmd.getCmdType(), backoffSeconds, msg);
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > 480 ? s.substring(0, 480) : s;
    }
}
