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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 雪花序列配置（前缀 starsite.sequence）——继承 core 的 {@link SequenceConfig} 完成绑定，
 * 本类只追加装配形态相关项。
 * <p>
 * 单机：{@code starsite.sequence.mode=standalone}（默认，worker-id 固定配置）；
 * 分布式：{@code starsite.sequence.mode=mysql}（sequence 表原子租约，需 DataSource）。
 *
 * @author muyi
 * @since 2026-10
 */
@ConfigurationProperties(prefix = "starsite.sequence")
public class SequenceProperties extends SequenceConfig {

    /** 组件总开关（false 时完全不装配） */
    private boolean enabled = true;

    /** 运行形态：standalone 单机 / mysql 分布式租约 */
    private Mode mode = Mode.STANDALONE;

    /** sequence 表名（mysql 形态） */
    private String tableName = "sys_sequence";

    /** 是否自动建表（IF NOT EXISTS，增量安全；false = 交由迁移脚本管理） */
    private boolean initDdl = true;

    public enum Mode { STANDALONE, MYSQL }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public boolean isInitDdl() { return initDdl; }
    public void setInitDdl(boolean initDdl) { this.initDdl = initDdl; }
}
