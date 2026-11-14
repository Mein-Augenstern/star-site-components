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
package com.xp.starsite.tuner.store.mybatis;

import com.xp.starsite.tuner.api.TunerAuditEntry;
import com.xp.starsite.tuner.api.TunerPreset;
import com.xp.starsite.tuner.api.TunerWindow;
import com.xp.starsite.tuner.clock.TimeSource;
import com.xp.starsite.tuner.store.TunerStore;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MyBatis 治理存储（消费方 classpath 自带 mybatis 时的零配置路径）：
 * 语句全部在 {@link TunerMapper} XML（同包资源，D3 决策）。
 *
 * @author muyi
 * @date 2026/11/14
 */
public class MyBatisTunerStore implements TunerStore {

    private static final Logger log = LoggerFactory.getLogger(MyBatisTunerStore.class);

    /** 表名白名单 */
    private static final java.util.regex.Pattern SAFE_IDENTIFIER =
            java.util.regex.Pattern.compile("[A-Za-z0-9_]+");

    /** MyBatis Mapper */
    private final TunerMapper mapper;

    /** 四表名（前缀渲染） */
    private final String presetTable;
    private final String windowTable;
    private final String bindingTable;
    private final String auditTable;

    /** id 发号（sequence 组件） */
    private final LongSupplier idGenerator;

    /** 时间源（审计时刻） */
    private final TimeSource time;

    /**
     * 全参构造。
     *
     * @param mapper      MyBatis Mapper
     * @param tablePrefix 表前缀（白名单 [A-Za-z0-9_]）
     * @param initDdl     true 构造期建表（失败只 warn 不阻断——DBA/迁移脚本管理形态）
     * @param idGenerator id 发号器
     * @param time        时间源
     * @throws IllegalArgumentException 前缀含白名单外字符
     */
    public MyBatisTunerStore(TunerMapper mapper, String tablePrefix, boolean initDdl,
                             LongSupplier idGenerator, TimeSource time) {
        if (tablePrefix == null || !SAFE_IDENTIFIER.matcher(tablePrefix).matches()) {
            throw new IllegalArgumentException("表前缀仅允许字母数字下划线: " + tablePrefix);
        }
        this.mapper = mapper;
        this.presetTable = tablePrefix + "_preset";
        this.windowTable = tablePrefix + "_window";
        this.bindingTable = tablePrefix + "_binding";
        this.auditTable = tablePrefix + "_audit";
        this.idGenerator = idGenerator;
        this.time = time;
        if (initDdl) {
            for (Runnable ddl : new Runnable[]{() -> mapper.createPresetTable(presetTable),
                    () -> mapper.createWindowTable(windowTable),
                    () -> mapper.createBindingTable(bindingTable),
                    () -> mapper.createAuditTable(auditTable)}) {
                try {
                    ddl.run();
                } catch (RuntimeException e) {
                    log.warn("[starsite-tuner] 建表检查失败（不阻断启动）: {}", e.getMessage());
                }
            }
            log.info("[starsite-tuner] 治理三表就绪（mybatis，前缀 {}）", tablePrefix);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void upsertPreset(TunerPreset preset) {
        mapper.upsertPreset(presetTable, idGenerator.getAsLong(), preset);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<TunerPreset> preset(String name) {
        return Optional.ofNullable(mapper.findPreset(presetTable, name));
    }

    /** {@inheritDoc} */
    @Override
    public List<TunerPreset> presets() {
        return mapper.listPresets(presetTable);
    }

    /** {@inheritDoc} */
    @Override
    public void bindDefault(String poolName, String presetName) {
        mapper.upsertBinding(bindingTable, idGenerator.getAsLong(), poolName, presetName);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<String> defaultPreset(String poolName) {
        return Optional.ofNullable(mapper.findBinding(bindingTable, poolName));
    }

    /** {@inheritDoc} */
    @Override
    public TunerWindow upsertWindow(TunerWindow window) {
        if (window.getId() > 0) {
            mapper.updateWindow(windowTable, window);
            return window;
        }
        long id = idGenerator.getAsLong();
        mapper.insertWindow(windowTable, id, window);
        return new TunerWindow(id, window.getPoolName(), window.getPresetName(),
                window.getStartCron(), window.getEndCron());
    }

    /** {@inheritDoc} */
    @Override
    public void removeWindow(long id) {
        mapper.removeWindow(windowTable, id);
    }

    /** {@inheritDoc} */
    @Override
    public List<TunerWindow> windows(String poolName) {
        return mapper.listWindows(windowTable, poolName);
    }

    /** {@inheritDoc} 审计 id 由本处发号（引擎传 0） */
    @Override
    public void appendAudit(TunerAuditEntry entry) {
        TunerAuditEntry withId = new TunerAuditEntry(idGenerator.getAsLong(),
                entry.getOccurredAt() == null ? time.now() : entry.getOccurredAt(),
                entry.getHost(), entry.getPoolName(), entry.getPresetName(), entry.getSource(),
                entry.getOldCore(), entry.getOldMax(), entry.getOldKeep(),
                entry.getNewCore(), entry.getNewMax(), entry.getNewKeep());
        mapper.appendAudit(auditTable, withId);
    }

    /** {@inheritDoc} */
    @Override
    public List<TunerAuditEntry> recentAudits(int limit) {
        return mapper.recentAudits(auditTable, Math.max(1, limit));
    }

    /** {@inheritDoc} ISO 字符串解析为毫秒（JDBC 形态同语义：写后必变） */
    @Override
    public long versionStamp() {
        String stamp = mapper.versionStamp(presetTable, windowTable, bindingTable, auditTable);
        if (stamp == null) {
            return 0L;
        }
        try {
            return java.time.LocalDateTime.parse(stamp.replace(' ', 'T'))
                    .toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        } catch (Exception e) {
            log.warn("[starsite-tuner] 版本戳解析失败（按每次变更处理）: {}", stamp);
            return System.nanoTime();
        }
    }

    /** {@inheritDoc} */
    @Override
    public long countBindings() {
        return mapper.countBindings(bindingTable);
    }
}
