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
package com.xp.starsite.tuner.admin;


/**
 * 治理端点守卫 SPI（三级鉴权第一优先级——接公司内部鉴权体系）：
 * 自定义 Bean 即让位内置 token 守卫（async AdminGuard 同款机制）。
 *
 * @author muyi
 * @date 2026/11/14
 */
public interface TunerAdminGuard {

    /**
     * 判定请求是否放行。
     *
     * @param request 治理请求
     * @return true 放行
     */
    boolean allow(java.util.Map<String, String> headers);
}
