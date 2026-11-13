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
package com.xp.starsite.idempotent.api;

/**
 * 幂等保护的业务动作（门面路径的业务载体）：返回值即回放载荷（字符串自管——
 * core 零 JSON 依赖，注解路径的 Jackson 序列化在 starter）。
 *
 * <p>抛异常 = 执行失败（键释放，记录 FAILED；下次同键新请求重新执行）；
 * 正常返回 = 成功（载荷存储，窗口内回放）。
 *
 * @author muyi
 * @date 2026/11/12
 */
@FunctionalInterface
public interface IdempotentAction {

    /**
     * 执行业务动作。
     *
     * @return 回放载荷（void 语义返回 null——回放即空返回）
     * @throws Exception 业务失败（引擎标记 FAILED 后原样重抛/包装重抛）
     */
    String run() throws Exception;
}
