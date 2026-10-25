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
package com.xp.starsite.lock;

import com.xp.starsite.lock.model.LockCoreConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分布式锁配置（前缀 starsite.lock）——继承 core 的 LockCoreConfig 并追加装配形态项。
 *
 * @author muyi
 * @date 2026/10/25
 */
@ConfigurationProperties(prefix = "starsite.lock")
public class LockProperties extends LockCoreConfig {

    /** 组件总开关（false 时完全不装配）。 */
    private boolean enabled = true;

    /** 存储形态：db（MyBatis Mapper）/ redis（StringRedisTemplate）。 */
    private Mode mode = Mode.DB;

    /** 自动建锁表（仅 db 模式；IF NOT EXISTS 增量安全）。 */
    private boolean initDdl = true;

    /** 存储形态枚举：DB（MyBatis Mapper + 表）与 REDIS（StringRedisTemplate + key）。 */
    public enum Mode { DB, REDIS }

    /** 读取组件总开关。@return true 启用（默认） */
    public boolean isEnabled() { return enabled; }
    /** 设置组件总开关。@param enabled true 启用 */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    /** 读取存储形态。@return 存储形态（默认 DB） */
    public Mode getMode() { return mode; }
    /** 设置存储形态。@param mode DB / REDIS */
    public void setMode(Mode mode) { this.mode = mode; }
    /** 读取自动建表开关。@return true 自动建表（默认） */
    public boolean isInitDdl() { return initDdl; }
    /** 设置自动建表开关。@param initDdl true 自动建表 */
    public void setInitDdl(boolean initDdl) { this.initDdl = initDdl; }
}
