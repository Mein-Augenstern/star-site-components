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

import java.util.List;

/**
 * Markdown 报告生成器——可直接贴到 GitHub PR / docs/。
 *
 * @author muyi
 */
public class BenchReporter {

    /**
     * 生成 Markdown 性能报告。
     */
    public static String report(List<BenchResult> results, String environment) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Performance Benchmark Report\n\n");
        sb.append("> Environment: ").append(environment).append("\n");
        sb.append("> Date: ").append(java.time.LocalDate.now()).append("\n");
        sb.append("> Duration per scenario: fixed iterations (see notes)\n\n");

        // 按组件分组
        for (String comp : List.of("sequence", "lock", "async-command")) {
            var compResults = results.stream()
                    .filter(r -> r.component.equals(comp))
                    .toList();
            if (compResults.isEmpty()) continue;

            sb.append("## ").append(comp).append("\n\n");
            sb.append("| Scenario | Threads | ops/sec | P50 | P95 | P99 | P99.9 | Max | Notes |\n");
            sb.append("|---|---|---|---|---|---|---|---|---|\n");
            for (BenchResult r : compResults) {
                sb.append(String.format("| %s | %d | %,.0f | %s | %s | %s | %s | %s | %s |\n",
                        r.scenario, r.threads, r.opsPerSec,
                        r.fmtNanos(r.p50Nanos), r.fmtNanos(r.p95Nanos),
                        r.fmtNanos(r.p99Nanos), r.fmtNanos(r.p999Nanos),
                        r.fmtNanos(r.maxNanos), r.notes));
            }
            sb.append("\n");

            // 校验结果
            var validations = compResults.stream()
                    .flatMap(r -> r.validations.stream())
                    .toList();
            if (!validations.isEmpty()) {
                sb.append("### Validations\n\n");
                for (String v : validations) sb.append("- ").append(v).append("\n");
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
