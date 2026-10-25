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
package com.xp.starsite.sequence.mybatis;

import com.xp.starsite.sequence.allocator.WorkerIdAllocator;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MyBatis 适配自动装配：classpath 存在 MyBatis（SqlSessionFactory）且 mode=mysql 时，
 * workerId 分配器优先装配 MyBatis 版（SQL 在 Mapper 注解里，消费方 MyBatis 生态原生生效）。
 * <p>
 * 事务模板不可少：LAST_INSERT_ID 是 MySQL 连接级会话变量，
 * takeFromGlobalPool + selectLastInsertId 必须同一连接——编程式事务保证连接不归还。
 *
 * @author muyi
 * @date 2026/10/25
 */
@AutoConfiguration
@ConditionalOnClass(SqlSessionFactory.class)
@ConditionalOnProperty(prefix = "starsite.sequence", name = "enabled", havingValue = "true", matchIfMissing = true)
@MapperScan(basePackages = "com.xp.starsite.sequence.mybatis", annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class SequenceMyBatisAutoConfiguration {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(SequenceMyBatisAutoConfiguration.class);

    /**
     * 装配 MyBatis 版分配器（优先于 core 的 raw JDBC 版）。
     * <p>装配条件：mode=mysql + classpath 有 MyBatis；让位：应用已自定义 WorkerIdAllocator。
     *
     * @param mapper               MyBatis Mapper
     * @param props                序列配置
     * @param env                  Spring 环境（服务名/pid）
     * @param sqlSessionFactory    MyBatis 会话工厂（连接管理）
     * @param transactionManager   事务管理器（保证 LAST_INSERT_ID 同连接两步）
     * @return MyBatis 版 workerId 分配器
     */
    @Bean
    @ConditionalOnMissingBean(WorkerIdAllocator.class)
    @ConditionalOnProperty(prefix = "starsite.sequence", name = "mode", havingValue = "mysql")
    public WorkerIdAllocator myBatisMysqlWorkerIdAllocator(
            SequenceMapper mapper,
            com.xp.starsite.sequence.SequenceProperties props,
            Environment env,
            SqlSessionFactory sqlSessionFactory,
            ObjectProvider<PlatformTransactionManager> transactionManager,
            ObjectProvider<com.xp.starsite.sequence.facade.StarsiteIdGenerator> idGenProvider) {
        log.info("[starsite-sequence] mysql 租约分配器：mybatis（消费方 MyBatis 生态原生生效）");
        String appName = env.getProperty("spring.application.name", "unknown");
        String instanceTag = props.getInstanceTag() != null && !props.getInstanceTag().isBlank()
                ? props.getInstanceTag()
                : appName + "@" + ProcessHandle.current().pid();
        SqlSessionTemplate sessionTemplate = new SqlSessionTemplate(sqlSessionFactory);
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager.getObject());
        // 审计行主键 id 生成器：优先 sequence 门面；缺省回退 workerId 派生（自举阶段安全）
        com.xp.starsite.sequence.facade.StarsiteIdGenerator idGen = idGenProvider.getIfAvailable();
        java.util.function.LongSupplier idGenerator = idGen != null
                ? idGen::next
                : () -> 1;  // 自举兜底（__global__ 种子行为保留 id=1，审计行随后覆盖更新）
        return new MyBatisMysqlWorkerIdAllocator(
                mapper, sessionTemplate, txTemplate,
                props.getTableName(), appName, instanceTag,
                props.getWorkerIdBits(), props.getWraparoundPolicy().name(), idGenerator);
    }
}
