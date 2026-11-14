package com.starsite.boot27;

import static org.assertj.core.api.Assertions.assertThat;

import com.xp.starsite.asynccmd.client.AsyncCommandClient;
import com.xp.starsite.idempotent.api.IdempotentClient;
import com.xp.starsite.lock.client.LockClient;
import com.xp.starsite.quota.api.QuotaClient;
import com.xp.starsite.schedule.api.ScheduleClient;
import com.xp.starsite.sequence.facade.StarsiteIdGenerator;
import com.xp.starsite.tuner.api.TunerClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boot 2.7 端到端验证：七组件 starter 在 Boot 2.7 JVM 中装配 + 核心门面可用。
 *
 * <p>验证点：
 * <ul>
 *   <li>spring.factories + imports 双通道注册——Boot 2.7 读 spring.factories 装配全部七 starter</li>
 *   <li>所有门面 Bean 可注入（自动装配未失败）</li>
 *   <li>sequence 发号、lock 争锁、quota 占用在 H2 内存库上端到端可用</li>
 * </ul>
 *
 * @author boot27-verify
 */
@SpringBootTest
class Boot27VerifyTest {

    /** 雪花 ID 发号器 */
    @Autowired
    private StarsiteIdGenerator idGenerator;

    /** 分布式锁 */
    @Autowired
    private LockClient lockClient;

    /** 异步命令 */
    @Autowired
    private AsyncCommandClient asyncCommandClient;

    /** 定时任务 */
    @Autowired
    private ScheduleClient scheduleClient;

    /** API 幂等键 */
    @Autowired
    private IdempotentClient idempotentClient;

    /** 余额型配额 */
    @Autowired
    private QuotaClient quotaClient;

    /** 线程池治理 */
    @Autowired
    private TunerClient tunerClient;

    /** 七组件门面全部装配（spring.factories 双通道验证） */
    @Test
    void allSevenComponentsWired() {
        assertThat(idGenerator).isNotNull();
        assertThat(lockClient).isNotNull();
        assertThat(asyncCommandClient).isNotNull();
        assertThat(scheduleClient).isNotNull();
        assertThat(idempotentClient).isNotNull();
        assertThat(quotaClient).isNotNull();
        assertThat(tunerClient).isNotNull();
    }

    /** sequence 端到端：H2 sys_sequence 租约 + 雪花发号 */
    @Test
    void sequenceEndToEnd() {
        long id = idGenerator.next();
        assertThat(id).isPositive();
        assertThat(idGenerator.nextStr()).isNotBlank();
    }

    /** lock 端到端：H2 starsite_lock 争锁/释放 */
    @Test
    void lockEndToEnd() {
        assertThat(lockClient.tryLock("boot27-test")).isTrue();
        lockClient.unlock("boot27-test");
    }

    /** quota 端到端：建户 + 占用 + 恰好满额 + 超额拒绝 */
    @Test
    void quotaEndToEnd() {
        quotaClient.init("boot27-quota", 100);
        assertThat(quotaClient.occupy("boot27-quota", 60).getAvailable()).isEqualTo(40);
        assertThat(quotaClient.occupy("boot27-quota", 40).getAvailable()).isEqualTo(0); // 60+40=100 恰满
        try {
            quotaClient.occupy("boot27-quota", 1);
            throw new AssertionError("should exceed");
        } catch (com.xp.starsite.quota.api.QuotaExceededException expected) {
            assertThat(expected.getAvailable()).isEqualTo(0);
        }
    }
}
