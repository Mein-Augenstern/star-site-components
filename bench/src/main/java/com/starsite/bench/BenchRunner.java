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

import com.starsite.bench.infra.H2Infra;
import com.starsite.bench.scenario.AsyncBench;
import com.starsite.bench.scenario.IdempotentBench;
import com.starsite.bench.scenario.LockBench;
import com.starsite.bench.scenario.QuotaBench;
import com.starsite.bench.scenario.ScheduleBench;
import com.starsite.bench.scenario.SequenceBench;
import com.starsite.bench.scenario.TunerBench;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;

/**
 * 基准主入口：按组件执行全部场景并生成 Markdown 报告。
 *
 * 用法：
 *   mvn compile exec:java                           # 全场景（七组件）
 *   mvn compile exec:java -Dexec.args="sequence"    # 仅 sequence
 *   mvn compile exec:java -Dexec.args="lock"        # 仅 lock
 *   mvn compile exec:java -Dexec.args="async"       # 仅 async-command
 *   mvn compile exec:java -Dexec.args="schedule"    # 仅 schedule
 *   mvn compile exec:java -Dexec.args="idempotent"  # 仅 idempotent
 *   mvn compile exec:java -Dexec.args="quota"       # 仅 quota
 *   mvn compile exec:java -Dexec.args="tuner"       # 仅 tuner
 */
public class BenchRunner {

    public static void main(String[] args) throws Exception {
        String filter = args.length > 0 ? args[0].toLowerCase() : "all";
        List<BenchResult> results = new ArrayList<>();

        DataSource ds = H2Infra.dataSource();
        SqlSessionFactory factory = H2Infra.sqlSessionFactory(ds);

        System.out.println("=".repeat(80));
        System.out.println("  starsite-components Benchmark Suite (7 components)");
        System.out.println("  JDK: " + System.getProperty("java.version")
                + " | Storage: H2 in-memory (lock/async) + memory stores (其余五组件)");
        System.out.println("=".repeat(80));

        // ===== SEQUENCE =====
        if (filter.equals("all") || filter.equals("sequence")) {
            System.out.println("\n--- SEQUENCE ---");
            results.add(run("S1 single-thread", SequenceBench::singleThread));
            for (int threads : SequenceBench.THREAD_COUNTS) {
                final int t = threads;
                results.add(run("S2 " + threads + "-thread", () -> SequenceBench.multiThread(t)));
            }
        }

        // ===== LOCK =====
        if (filter.equals("all") || filter.equals("lock")) {
            System.out.println("\n--- LOCK ---");
            results.add(run("L1 uncontended", () -> LockBench.uncontended(ds, factory)));
            results.add(run("L2 8-thread", () -> LockBench.contended(ds, factory, 8)));
            results.add(run("L2 16-thread", () -> LockBench.contended(ds, factory, 16)));
            results.add(run("L2 32-thread", () -> LockBench.contended(ds, factory, 32)));
            results.add(run("L3 reentrant", () -> LockBench.reentrant(ds, factory)));
        }

        // ===== ASYNC =====
        if (filter.equals("all") || filter.equals("async")) {
            System.out.println("\n--- ASYNC-COMMAND ---");
            results.add(run("A1 memory submit", AsyncBench::memorySubmit));
            results.add(run("A2 end-to-end", () -> AsyncBench.endToEnd(ds, factory)));
            results.add(run("A3 claim fairness", () -> AsyncBench.claimFairness(ds, factory)));
            results.add(run("A4 memory full-chain", () -> AsyncBench.memoryFullChain(1)));
            results.add(run("A4 memory full-chain 16-thread", () -> AsyncBench.memoryFullChain(16)));
        }

        // ===== SCHEDULE =====
        if (filter.equals("all") || filter.equals("schedule")) {
            System.out.println("\n--- SCHEDULE ---");
            results.add(run("SC1 window-hit single", ScheduleBench::windowHitSingle));
            results.add(run("SC2 window-hit 16-thread", () -> ScheduleBench.windowHitThreads(16)));
        }

        // ===== IDEMPOTENT =====
        if (filter.equals("all") || filter.equals("idempotent")) {
            System.out.println("\n--- IDEMPOTENT ---");
            results.add(run("I1 first-exec", () -> IdempotentBench.firstExec(1)));
            results.add(run("I1 first-exec 16-thread", () -> IdempotentBench.firstExec(16)));
            results.add(run("I2 replay", () -> IdempotentBench.replay(1)));
            results.add(run("I2 replay 16-thread", () -> IdempotentBench.replay(16)));
            results.add(run("I3 exactly-once race 16-thread", IdempotentBench::exactlyOnceRace));
        }

        // ===== QUOTA =====
        if (filter.equals("all") || filter.equals("quota")) {
            System.out.println("\n--- QUOTA ---");
            results.add(run("Q1 occupy/release", () -> QuotaBench.occupyRelease(1)));
            results.add(run("Q1 occupy/release 16-thread", () -> QuotaBench.occupyRelease(16)));
        }

        // ===== TUNER =====
        if (filter.equals("all") || filter.equals("tuner")) {
            System.out.println("\n--- TUNER ---");
            results.add(run("T1 applyAll steady-state", () -> TunerBench.applyAll(1)));
            results.add(run("T1 applyAll steady-state 16-thread", () -> TunerBench.applyAll(16)));
        }

        // ===== REPORT =====
        System.out.println("\n" + "=".repeat(80));
        String env = "JDK " + System.getProperty("java.version")
                + " | H2 in-memory (MODE=MySQL) + memory stores"
                + " | " + Runtime.getRuntime().availableProcessors() + " cores";
        String report = BenchReporter.report(results, env);
        System.out.println(report);

        // 写入文件
        String outputPath = "bench-report.md";
        java.nio.file.Files.writeString(java.nio.file.Path.of(outputPath), report);
        System.out.println("Report saved to: " + outputPath);
    }

    interface BenchSupplier {
        BenchResult get() throws Exception;
    }

    static BenchResult run(String label, BenchSupplier supplier) {
        System.out.print("  Running " + label + "...");
        System.out.flush();
        long start = System.currentTimeMillis();
        try {
            BenchResult r = supplier.get();
            long elapsed = System.currentTimeMillis() - start;
            System.out.println(" done (" + elapsed + "ms)");
            System.out.println("    " + r);
            for (String v : r.validations) System.out.println("    " + v);
            return r;
        } catch (Exception e) {
            System.out.println(" FAILED: " + e.getMessage());
            e.printStackTrace();
            return new BenchResult(label, "unknown", 0, 0, 0, 0, 0, 0, 0, 0, "ERROR: " + e.getMessage());
        }
    }
}
