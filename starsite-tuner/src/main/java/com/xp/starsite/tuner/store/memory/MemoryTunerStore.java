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
package com.xp.starsite.tuner.store.memory;

import com.xp.starsite.tuner.api.TunerAuditEntry;
import com.xp.starsite.tuner.api.TunerPreset;
import com.xp.starsite.tuner.api.TunerWindow;
import com.xp.starsite.tuner.clock.TimeSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存治理存储（单机形态：全内存，重启丢治理配置——单机开发/测试用；
 * 分布式必须 jdbc/mybatis——参数源要共享才收敛）。与表形态语义等价：
 * upsert 按名幂等、versionStamp 写后必变。
 *
 * @author muyi
 * @date 2026/11/14
 */
public class MemoryTunerStore implements com.xp.starsite.tuner.store.TunerStore {

    /** 预案表（name → preset） */
    private final Map<String, TunerPreset> presets = new ConcurrentHashMap<>();

    /** 窗口表（id → window） */
    private final Map<Long, TunerWindow> windows = new ConcurrentHashMap<>();

    /** 绑定表（pool → presetName） */
    private final Map<String, String> bindings = new ConcurrentHashMap<>();

    /** 审计表（追加） */
    private final List<TunerAuditEntry> audits = new ArrayList<>();

    /** 窗口发号 */
    private final AtomicLong windowIds = new AtomicLong();

    /** 审计发号 */
    private final AtomicLong auditIds = new AtomicLong();

    /** 版本戳（任何写 +1——写后必变契约） */
    private final AtomicLong version = new AtomicLong();

    /** 时间源（审计时刻） */
    private final TimeSource time;

    /**
     * 构造。
     *
     * @param time 时间源
     */
    public MemoryTunerStore(TimeSource time) {
        this.time = time;
    }

    /** {@inheritDoc} */
    @Override
    public void upsertPreset(TunerPreset preset) {
        presets.put(preset.getPresetName(), preset);
        version.incrementAndGet();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<TunerPreset> preset(String name) {
        return Optional.ofNullable(presets.get(name));
    }

    /** {@inheritDoc} */
    @Override
    public List<TunerPreset> presets() {
        return new ArrayList<>(presets.values());
    }

    /** {@inheritDoc} */
    @Override
    public void bindDefault(String poolName, String presetName) {
        bindings.put(poolName, presetName);
        version.incrementAndGet();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<String> defaultPreset(String poolName) {
        return Optional.ofNullable(bindings.get(poolName));
    }

    /** {@inheritDoc} */
    @Override
    public TunerWindow upsertWindow(TunerWindow window) {
        TunerWindow saved;
        if (window.getId() > 0 && windows.containsKey(window.getId())) {
            saved = new TunerWindow(window.getId(), window.getPoolName(), window.getPresetName(),
                    window.getStartCron(), window.getEndCron());
            windows.put(saved.getId(), saved);
        } else {
            saved = new TunerWindow(windowIds.incrementAndGet(), window.getPoolName(),
                    window.getPresetName(), window.getStartCron(), window.getEndCron());
            windows.put(saved.getId(), saved);
        }
        version.incrementAndGet();
        return saved;
    }

    /** {@inheritDoc} */
    @Override
    public void removeWindow(long id) {
        windows.remove(id);
        version.incrementAndGet();
    }

    /** {@inheritDoc} */
    @Override
    public List<TunerWindow> windows(String poolName) {
        List<TunerWindow> out = new ArrayList<>();
        for (TunerWindow w : windows.values()) {
            if (poolName == null || w.getPoolName().equals(poolName)) {
                out.add(w);
            }
        }
        out.sort(Comparator.comparingLong(TunerWindow::getId));
        return out;
    }

    /** {@inheritDoc} */
    @Override
    public void appendAudit(TunerAuditEntry entry) {
        audits.add(new TunerAuditEntry(auditIds.incrementAndGet(), entry.getOccurredAt(),
                entry.getHost(), entry.getPoolName(), entry.getPresetName(), entry.getSource(),
                entry.getOldCore(), entry.getOldMax(), entry.getOldKeep(),
                entry.getNewCore(), entry.getNewMax(), entry.getNewKeep()));
        version.incrementAndGet();
    }

    /** {@inheritDoc} */
    @Override
    public List<TunerAuditEntry> recentAudits(int limit) {
        List<TunerAuditEntry> out = new ArrayList<>(audits);
        out.sort(Comparator.comparingLong(TunerAuditEntry::getId).reversed());
        return out.subList(0, Math.min(limit, out.size()));
    }

    /** {@inheritDoc} */
    @Override
    public long versionStamp() {
        return version.get();
    }

    /** {@inheritDoc} */
    @Override
    public long countBindings() {
        return bindings.size();
    }
}
