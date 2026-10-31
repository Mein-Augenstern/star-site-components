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
package com.starsite.bench;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个基准场景的测量结果。
 *
 * @author muyi
 */
public class BenchResult {
    public final String scenario;
    public final String component;
    public final int threads;
    public final long totalOps;
    public final double opsPerSec;
    public final long p50Nanos;
    public final long p95Nanos;
    public final long p99Nanos;
    public final long p999Nanos;
    public final long maxNanos;
    public final String notes;
    public final List<String> validations = new ArrayList<>();

    public BenchResult(String scenario, String component, int threads,
                       long totalOps, double opsPerSec,
                       long p50, long p95, long p99, long p999, long max,
                       String notes) {
        this.scenario = scenario;
        this.component = component;
        this.threads = threads;
        this.totalOps = totalOps;
        this.opsPerSec = opsPerSec;
        this.p50Nanos = p50;
        this.p95Nanos = p95;
        this.p99Nanos = p99;
        this.p999Nanos = p999;
        this.maxNanos = max;
        this.notes = notes;
    }

    public BenchResult pass(String check) {
        validations.add("✅ " + check);
        return this;
    }

    public BenchResult fail(String check) {
        validations.add("❌ " + check);
        return this;
    }

    public String fmtNanos(long nanos) {
        if (nanos < 1_000) return nanos + "ns";
        if (nanos < 1_000_000) return String.format("%.1fμs", nanos / 1000.0);
        if (nanos < 1_000_000_000) return String.format("%.2fms", nanos / 1_000_000.0);
        return String.format("%.2fs", nanos / 1_000_000_000.0);
    }

    @Override
    public String toString() {
        return String.format("[%s] %s | threads=%d | %,.0f ops/s | P50=%s P95=%s P99=%s P99.9=%s max=%s",
                component, scenario, threads, opsPerSec,
                fmtNanos(p50Nanos), fmtNanos(p95Nanos), fmtNanos(p99Nanos), fmtNanos(p999Nanos), fmtNanos(maxNanos));
    }
}
