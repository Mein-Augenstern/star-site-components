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

import com.xp.starsite.sequence.allocator.FixedWorkerIdAllocator;
import com.xp.starsite.sequence.allocator.MysqlWorkerIdAllocator;
import com.xp.starsite.sequence.allocator.WorkerIdAllocator;
import com.xp.starsite.sequence.facade.StarsiteIdGenerator;
import com.xp.starsite.sequence.generator.SnowflakeIdGenerator;
import com.xp.starsite.sequence.health.StarsiteSequenceHealthIndicator;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

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
 * @date 2026/10/25
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "starsite.sequence", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SequenceProperties.class)
public class StarsiteSequenceAutoConfiguration {

    /** 组件装配日志器（记录就绪信息与形态/机器位描述） */
    private static final Logger log = LoggerFactory.getLogger(StarsiteSequenceAutoConfiguration.class);

    /**
     * 装配发号器门面 {@link StarsiteIdGenerator}——本组件的核心 Bean。
     * <p>装配条件：starsite.sequence.enabled 缺省或为 true（见类上 @ConditionalOnProperty），
     * 且容器中尚无任何 StarsiteIdGenerator Bean（@ConditionalOnMissingBean）；
     * 应用自备同类型 Bean 时本方法让位，完全采用应用自定义实例。
     * <p>流程：启动期参数校验 → 按形态构建机器位分配器（standalone 取固定配置值；
     * mysql 从 sys_sequence 表 __global__ 池原子租约，缺 DataSource 快速失败）→
     * 以租到的 worker-id 与继承自 {@link SequenceConfig} 的雪花参数构造发号器，输出就绪日志。
     *
     * @param props              雪花序列配置项（含继承自 core 的位宽/纪元/回拨策略等）
     * @param dataSourceProvider DataSource 惰性提供器：仅 mysql 形态取用；单机形态不触碰，
     *                           故容器/classpath 中无 DataSource 也能正常启动
     * @return 就绪的发号器门面（内含底层雪花发号器、机器位与形态描述）
     * @throws IllegalArgumentException 位宽或（单机形态下）worker-id 配置非法（见 {@link #validate}）
     * @throws IllegalStateException    mysql 形态但容器中无 DataSource（提示改用 standalone）
     * @throws Exception                mysql 形态下机器位租约失败（建表/取号 SQL 异常沿
     *                                  {@code WorkerIdAllocator.allocate()} 上抛）
     */
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

    /**
     * 装配序列组件健康端点 {@link StarsiteSequenceHealthIndicator}（actuator 附属能力）。
     * <p>装配条件（须同时满足）：classpath 存在 actuate 的 HealthIndicator 类
     * （@ConditionalOnClass——未引 actuator 时本 Bean 连同装配动作完全不生效）；
     * 容器中不存在名为 starsiteSequenceHealthIndicator 的 Bean
     * （@ConditionalOnMissingBean(name=...)——应用自备同名 Bean 时让位）。
     * 依赖主装配 {@link #starsiteIdGenerator} 产出的发号器门面。
     *
     * @param generator 主装配产出的发号器门面（仅读取机器位与形态描述，被动上报不消耗号段）
     * @return 健康端点实例（恒 UP，明细含 workerId/allocator）
     */
    @Bean
    @ConditionalOnMissingBean(name = "starsiteSequenceHealthIndicator")
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    public StarsiteSequenceHealthIndicator starsiteSequenceHealthIndicator(StarsiteIdGenerator generator) {
        return new StarsiteSequenceHealthIndicator(generator);
    }

    /**
     * 启动期参数合法性校验（fail-fast：非法配置在装配阶段即拒绝启动，不留到运行期发号时才暴露）。
     * <ul>
     *   <li>worker-id-bits 与 sequence-bits 各自须在 1~21，且合计不超过 22（时间戳至少保留 41 位）</li>
     *   <li>standalone 形态下 worker-id 须落在 0 ~ 2^worker-id-bits - 1 区间</li>
     * </ul>
     *
     * @param props 雪花序列配置项
     * @throws IllegalArgumentException 任一约束不满足时抛出，消息携带当前值与合法区间
     */
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

    /**
     * 按运行形态构建机器位分配器。
     * <ul>
     *   <li>STANDALONE：固定值分配器，机器位直接取配置 worker-id，无外部依赖</li>
     *   <li>MYSQL：MySQL 租约分配器，从 sequence 表 __global__ 池原子自增取号；
     *       instance-tag 未配置（null/空白）时自动生成「服务名@pid@启动毫秒」作实例审计标识</li>
     * </ul>
     *
     * @param props              雪花序列配置项（形态/表名/服务名/实例标识/建表开关/位宽/回绕策略）
     * @param dataSourceProvider DataSource 惰性提供器（仅 MYSQL 形态取用）
     * @return 与配置形态匹配的机器位分配器
     * @throws IllegalStateException MYSQL 形态但容器中无 DataSource 时抛出（提示改用 standalone）
     */
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
