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
package com.xp.starsite.schedule;

import com.xp.starsite.lock.client.LockClient;
import com.xp.starsite.schedule.api.MisfirePolicy;
import com.xp.starsite.schedule.api.StarsiteScheduled;
import com.xp.starsite.schedule.core.ScheduleTaskRegistry;
import com.xp.starsite.schedule.core.ScheduledTaskDef;
import com.xp.starsite.schedule.trigger.CronTrigger;
import com.xp.starsite.schedule.trigger.DailyAtTrigger;
import com.xp.starsite.schedule.trigger.FixedDelayTrigger;
import com.xp.starsite.schedule.trigger.Trigger;
import java.lang.reflect.Method;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * {@link StarsiteScheduled @StarsiteScheduled} 注解扫描注册器：Bean 初始化后扫方法注解，
 * 校验触发器配置（三选一）并注册进 {@link ScheduleTaskRegistry}。
 *
 * <p>时序保证：BPP 在 Bean 创建期注册任务，引擎由 SmartLifecycle（phase MAX）在
 * 容器就绪后启动——注册必然先于启动，无锁竞争窗口。
 *
 * <p>校验规则（装配期快速失败，不静默降级）：
 * <ul>
 *   <li>触发器 cron/dailyAt/fixedDelaySeconds 恰配一个</li>
 *   <li>zone 非法、dailyAt 非 HH:mm、cron 解析失败——注解处直接抛</li>
 *   <li>guard=true 但容器无 {@link LockClient} Bean——注册失败并说明（引
 *       starsite-lock-spring-boot-starter 或将 guard 置 false）</li>
 *   <li>方法必须无参（有参任务不适用定时触发模型）</li>
 * </ul>
 *
 * @author muyi
 * @date 2026/11/02
 */
public class StarsiteScheduledBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(StarsiteScheduledBeanPostProcessor.class);

    /** 任务注册表 */
    private final ScheduleTaskRegistry registry;

    /** 分布式锁惰性提供器（guard 任务必需；BPP 创建早于 LockClient Bean——首次注册时解析） */
    private final org.springframework.beans.factory.ObjectProvider<LockClient> lockClientProvider;

    /** 环境抽象：注解属性支持 ${...} 占位符（Spring @Scheduled 同款 DX） */
    private final org.springframework.core.env.Environment environment;

    /** 注册计数（装配完成后的启动日志用） */
    private final AtomicBoolean warnedNoLock = new AtomicBoolean();

    /**
     * 构造。
     *
     * @param registry           任务注册表
     * @param lockClientProvider 分布式锁惰性提供器（guard 任务注册时解析）
     * @param environment        环境抽象（name/cron/dailyAt/zone 占位符解析）
     */
    public StarsiteScheduledBeanPostProcessor(ScheduleTaskRegistry registry,
            org.springframework.beans.factory.ObjectProvider<LockClient> lockClientProvider,
            org.springframework.core.env.Environment environment) {
        this.registry = registry;
        this.lockClientProvider = lockClientProvider;
        this.environment = environment;
    }

    /** {@inheritDoc} */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        for (Method method : bean.getClass().getDeclaredMethods()) {
            StarsiteScheduled anno = method.getAnnotation(StarsiteScheduled.class);
            if (anno == null) {
                continue;
            }
            registry.register(buildDef(bean, method, anno));
        }
        return bean;
    }

    /** 注解 → 任务定义（全部校验集中在此，异常信息定位到方法） */
    private ScheduledTaskDef buildDef(Object bean, Method method, StarsiteScheduled anno) {
        String where = bean.getClass().getSimpleName() + "#" + method.getName();
        if (method.getParameterCount() > 0) {
            throw new IllegalArgumentException("@StarsiteScheduled 方法必须无参: " + where);
        }
        // 占位符解析（无 ${} 原样返回；解析失败在触发器构造处统一暴露）
        String cron = environment.resolvePlaceholders(anno.cron());
        String dailyAt = environment.resolvePlaceholders(anno.dailyAt());
        String zoneText = environment.resolvePlaceholders(anno.zone());
        String nameText = environment.resolvePlaceholders(anno.name());
        boolean hasCron = !cron.isBlank();
        boolean hasDaily = !dailyAt.isBlank();
        boolean hasDelay = anno.fixedDelaySeconds() > 0;
        int picked = (hasCron ? 1 : 0) + (hasDaily ? 1 : 0) + (hasDelay ? 1 : 0);
        if (picked != 1) {
            throw new IllegalArgumentException(
                    "@StarsiteScheduled 触发器三选一（cron/dailyAt/fixedDelaySeconds）: " + where);
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(zoneText);
        } catch (Exception e) {
            throw new IllegalArgumentException("@StarsiteScheduled zone 非法: " + zoneText, e);
        }
        Trigger trigger = hasCron ? cron(cron, zone, where)
                : hasDaily ? dailyAt(dailyAt, zone, where)
                : new FixedDelayTrigger(anno.fixedDelaySeconds());
        LockClient lockClient = null;
        if (anno.guard()) {
            // 惰性解析（此时容器已就绪到任务 Bean 创建期，锁 Bean 可正常拉起）
            lockClient = lockClientProvider.getIfAvailable();
            if (lockClient == null) {
                if (warnedNoLock.compareAndSet(false, true)) {
                    log.error("[starsite-schedule] 容器缺少 LockClient Bean——guard 任务无法注册"
                            + "（引 starsite-lock-spring-boot-starter，或将 guard 置 false 走本地模式）");
                }
                throw new IllegalArgumentException("@StarsiteScheduled guard=true 需要 LockClient: " + where);
            }
        }
        String name = nameText.isBlank() ? where : nameText.trim();
        MisfirePolicy misfire = anno.misfire();
        Runnable body = () -> {
            try {
                method.invoke(bean);
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new IllegalStateException("任务体执行失败: " + where, cause);
            }
        };
        log.info("[starsite-schedule] 注册定时任务 {}: {}（misfire={}, guard={}）",
                name, trigger.describe(), misfire, anno.guard());
        return new ScheduledTaskDef(name, trigger, misfire, anno.guard(), body);
    }

    /** cron 触发器（解析失败定位到注解处） */
    private static Trigger cron(String cron, ZoneId zone, String where) {
        try {
            return new CronTrigger(cron, zone);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("@StarsiteScheduled cron 非法（" + where + "）: " + cron, e);
        }
    }

    /** dailyAt 触发器（HH:mm 解析） */
    private static Trigger dailyAt(String dailyAt, ZoneId zone, String where) {
        String[] parts = dailyAt.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "@StarsiteScheduled dailyAt 需要 HH:mm（" + where + "）: " + dailyAt);
        }
        try {
            return new DailyAtTrigger(Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()), zone);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "@StarsiteScheduled dailyAt 非法（" + where + "）: " + dailyAt, e);
        }
    }
}
