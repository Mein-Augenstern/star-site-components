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
 * 调度触发模式：决定「谁来发起每轮 tick」——三种模式对应三种部署拓扑。
 * <ul>
 *   <li>{@link #COMPETING}——默认。所有实例各自轮询，令牌认领互斥
 *       （DB 天然分摊负载，无单点，适合 K8s 多副本）</li>
 *   <li>{@link #LEADER}——DB 锁选举 leader，仅 leader 实例发起 tick
 *       （减少数据库竞争压力；leader 崩溃后 TTL 到期其他实例自动接管，适合大集群+高频 tick）</li>
 *   <li>{@link #EXTERNAL}——组件不自跑调度，由外部调度器（XXL-Job / K8s CronJob / ElasticJob）
 *       经管控端点 {@code POST {admin-base-path}/tick} 触发 tick（适合已有调度平台统一管理的团队）</li>
 * </ul>
 * 三种模式的命令执行正确性等价——令牌认领互斥兜底，模式只影响触发效率与运维拓扑。
 *
 * @author muyi
 * @date 2026/10/25
 */
public enum ScheduleMode {

    /** 全实例竞争轮询：各自 tick，令牌认领互斥（默认，无单点） */
    COMPETING,

    /** DB 锁选举：仅 leader tick，TTL 过期自动接管（减少 DB 竞争压力） */
    LEADER,

    /** 外部触发：组件不自跑调度，外部调度器调 /tick 端点触发（XXL-Job / K8s CronJob / ElasticJob） */
    EXTERNAL
}
