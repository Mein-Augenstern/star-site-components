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
 * API 幂等键 starter（仅粘合，本体全在 core——core/starter 双模块铁律）：
 * @StarsiteIdempotent 注解代理（SpEL 键 + Jackson 结果回放）+ 存储自动装配
 * （jdbc/mybatis/memory）+ retention 过期看护（SmartLifecycle）+ 健康端点。
 *
 * <p>引 jar + 配置即用；@ConditionalOnMissingBean 全量让位（应用自定义任何
 * Store/Client/看护 Bean，自动装配整体让位）。
 *
 * @author muyi
 * @date 2026/11/12
 */
package com.xp.starsite.idempotent;
