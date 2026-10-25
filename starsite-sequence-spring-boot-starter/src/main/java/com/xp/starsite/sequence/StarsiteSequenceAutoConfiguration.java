package com.xp.starsite.sequence;

import com.xp.starsite.sequence.health.StarsiteSequenceHealthIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * starsite-sequence 自动装配（starter 模块，仅粘合；发号器本体在 core）：
 * 引 jar + 配置即用，无需 @Import 或 @EnableScheduling。
 * <pre>
 * 单机：starsite.sequence.mode=standalone（默认，worker-id 配置固定）
 * 分布式：starsite.sequence.mode=mysql（sys_sequence 表 __global__ 池原子租约）
 * </pre>
 * 应用自定义 {@link StarsiteIdGenerator} Bean 时本装配让位（@ConditionalOnMissingBean）；
 * actuator 在 classpath 时附赠健康端点（形态/机器位明细；被动上报，不消耗号段）。
 *
 * @author muyi
 * @since 2026-10
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "starsite.sequence", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SequenceProperties.class)
public class StarsiteSequenceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StarsiteSequenceAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public StarsiteIdGenerator starsiteIdGenerator(SequenceProperties props,
                                                    ObjectProvider<DataSource> dataSourceProvider) throws Exception {
        validate(props);
        WorkerIdAllocator allocator = buildAllocator(props, dataSourceProvider);
        long workerId = allocator.allocate();
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(
                workerId, props.getEpoch(), props.getWorkerIdBits(), props.getSequenceBits(),
                props.getMaxBackwardMillis(), props.getBackwardPolicy());
        StarsiteIdGenerator facade = new StarsiteIdGenerator(generator, workerId, allocator.describe());
        log.info("[starsite-sequence] 就绪：{}", facade.describe());
        return facade;
    }

    @Bean
    @ConditionalOnMissingBean(name = "starsiteSequenceHealthIndicator")
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    public StarsiteSequenceHealthIndicator starsiteSequenceHealthIndicator(StarsiteIdGenerator generator) {
        return new StarsiteSequenceHealthIndicator(generator);
    }

    private void validate(SequenceProperties props) {
        if (props.getWorkerIdBits() < 1 || props.getWorkerIdBits() > 21
                || props.getSequenceBits() < 1 || props.getSequenceBits() > 21
                || props.getWorkerIdBits() + props.getSequenceBits() > 22) {
            throw new IllegalArgumentException("starsite.sequence 位宽配置非法：worker-id-bits + sequence-bits "
                    + "须各自在 1~21 且合计不超过 22（时间戳至少 41 位），当前 "
                    + props.getWorkerIdBits() + "+" + props.getSequenceBits());
        }
        if (props.getMode() == SequenceProperties.Mode.STANDALONE) {
            long maxWorkerId = ~(-1L << props.getWorkerIdBits());
            if (props.getWorkerId() < 0 || props.getWorkerId() > maxWorkerId) {
                throw new IllegalArgumentException("starsite.sequence.worker-id 须在 0~" + maxWorkerId
                        + "（当前 " + props.getWorkerId() + "，worker-id-bits=" + props.getWorkerIdBits() + "）");
            }
        }
    }

    private WorkerIdAllocator buildAllocator(SequenceProperties props,
                                             ObjectProvider<DataSource> dataSourceProvider) {
        return switch (props.getMode()) {
            case STANDALONE -> new FixedWorkerIdAllocator(props.getWorkerId());
            case MYSQL -> {
                DataSource ds = dataSourceProvider.getIfAvailable();
                if (ds == null) {
                    throw new IllegalStateException(
                            "starsite.sequence.mode=mysql 需要 DataSource；单机场景请改 mode=standalone");
                }
                String tag = props.getInstanceTag() == null || props.getInstanceTag().isBlank()
                        ? "%s@%d@%d".formatted(props.getServiceName(), ProcessHandle.current().pid(),
                                System.currentTimeMillis())
                        : props.getInstanceTag();
                yield new MysqlWorkerIdAllocator(ds, props.getTableName(), props.getServiceName(), tag,
                        props.isInitDdl(), props.getWorkerIdBits(), props.getWraparoundPolicy());
            }
        };
    }
}
