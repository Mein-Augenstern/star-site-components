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

import com.xp.starsite.sequence.config.SequenceConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 雪花序列配置（前缀 starsite.sequence）——继承 core 的 {@link SequenceConfig} 完成绑定，
 * 本类只追加装配形态相关项（开关、形态、表名、建表开关）。
 * <p>
 * 单机：{@code starsite.sequence.mode=standalone}（默认，worker-id 固定配置）；
 * 分布式：{@code starsite.sequence.mode=mysql}（sequence 表原子租约，需 DataSource）。
 * 雪花参数（worker-id/位宽/纪元/回拨与回绕策略等）见父类 {@link SequenceConfig}。
 *
 * @author muyi
 * @date 2026/10/25
 */
@ConfigurationProperties(prefix = "starsite.sequence")
public class SequenceProperties extends SequenceConfig {

    /**
     * 组件总开关。
     * <p>默认 true；置 false 时自动装配整体不生效（容器中不出现任何序列相关 Bean），
     * 用于引入了 jar 但需临时停用发号组件的场景。
     */
    private boolean enabled = true;

    /**
     * 运行形态，默认 {@link Mode#STANDALONE}，决定机器位（worker-id）从何处取得。
     * <p>standalone：固定取配置值，无外部依赖；mysql：经 sequence 表原子租约，需 DataSource。
     */
    private Mode mode = Mode.STANDALONE;

    /**
     * sequence 表名（仅 mysql 形态生效）。
     * <p>默认 sys_sequence；仅允许字母/数字/下划线（core 分配器据此做防注入校验）。
     */
    private String tableName = "sys_sequence";

    /**
     * 是否自动建表（仅 mysql 形态生效）。
     * <p>默认 true——启动时 CREATE TABLE IF NOT EXISTS（增量安全，不动存量数据）；
     * 置 false 时表结构交由 DBA 迁移脚本统一管理。
     */
    private boolean initDdl = true;

    /**
     * 机器位分配形态：决定 worker-id 的来源与组件的外部依赖。
     */
    public enum Mode {
        /**
         * 单机形态：机器位直接取配置项 worker-id（默认 1），无任何外部依赖。
         * <p>适用：单实例部署、本地开发，或由外部编排系统（如 K8s StatefulSet 序号）
         * 保证各实例机器位互不相同的场景。
         */
        STANDALONE,
        /**
         * 分布式形态：机器位从 MySQL sequence 表 __global__ 全局池经原子自增租约取得，全平台唯一。
         * <p>依赖：容器中必须存在 DataSource，缺失时启动快速失败；适合多实例自由伸缩的部署。
         */
        MYSQL,
        /**
         * 分布式形态（Redis）：机器位从 Redis INCR 原子取号，亚毫秒级。
         * <p>依赖：容器中必须存在 StringRedisTemplate；无 MySQL 场景的轻量替代——
         * 取号更快但审计信息不如 MySQL 行可查（存 Redis hash）。
         */
        REDIS
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public boolean isInitDdl() { return initDdl; }
    public void setInitDdl(boolean initDdl) { this.initDdl = initDdl; }
}
