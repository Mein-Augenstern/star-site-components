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

/**
 * 参数预案与治理组件（七号组件 tuner，core 零 Spring Boot 依赖）——
 * 没有配置中心的团队的动态线程池治理（市面 DynamicTp/Hippo4j 均依赖配置中心或
 * 独立 Server，本组件参数源就是 MySQL/内存，零新部署）。
 *
 * <p>核心模型：预案（命名参数集）+ 窗口（cron 对：start/end）+ 绑定（池默认预案）
 * + 审计（per-host 变更留痕）。生效语义 = <b>纯时间函数</b>：窗口命中
 * {@code startCron.nextAfter(t) > endCron.nextAfter(t)}，各实例独立求值天然收敛——
 * 无推送、无 misfire（宕机跨窗口边界，重启后下一轮自动追平）。
 *
 * <p><b>边界</b>：v1 调 core/max/keepAlive（队列容量热调= v2 美团方案）；无界队列池
 * 的 max 参数无意义；窗口生效精度=轮询间隔；自适应自动调参= v2（v1 只有阈值告警钩子）。
 * 金融信贷方法论（隔离/预案/治理/兜底）见 docs/tuner-design.md。
 *
 * @author muyi
 * @date 2026/11/14
 */
package com.xp.starsite.tuner;
