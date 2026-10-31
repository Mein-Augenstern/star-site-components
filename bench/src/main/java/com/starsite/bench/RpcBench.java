package com.starsite.bench;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.HdrHistogram.Histogram;

/**
 * RPC 层压测工具：向已部署的 demo 服务发送 HTTP 请求，测量端到端延迟。
 * 覆盖三个组件的真实 RPC 路径：HTTP → Spring MVC → 组件 → MySQL → 响应。
 *
 * @author muyi
 */
public class RpcBench {

    static final String BASE_URL = System.getProperty("bench.url", "http://localhost:18080");
    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(70));
        System.out.println("  RPC Benchmark (HTTP → Spring → Component → MySQL)");
        System.out.println("  Target: " + BASE_URL);
        System.out.println("=".repeat(70));

        // 健康检查
        if (!healthCheck()) {
            System.err.println("服务不可达: " + BASE_URL);
            System.exit(1);
        }
        System.out.println("✅ 服务就绪\n");

        List<BenchResult> results = new ArrayList<>();

        // === 1. ID 生成（GET /api/demo/id） ===
        results.add(benchGet("RPC: snowflake ID (GET /api/demo/id)", "/api/demo/id", 1000, 1));
        results.add(benchGet("RPC: snowflake ID concurrent", "/api/demo/id", 2000, 8));
        results.add(benchGet("RPC: snowflake ID high-concurrency", "/api/demo/id", 3000, 16));

        // === 2. 分布式锁（GET /api/demo/lock?name=bench-lock） ===
        results.add(benchGet("RPC: lock acquire/release", "/api/demo/lock?name=bench-lock", 500, 1));
        results.add(benchGet("RPC: lock concurrent", "/api/demo/lock?name=bench-lock", 1000, 8));

        // === 3. 异步命令提交（POST /api/demo/notify） ===
        results.add(benchPost("RPC: async submit (POST /api/demo/notify)", "/api/demo/notify?userId=bench&message=test", 500, 1));
        results.add(benchPost("RPC: async submit concurrent", "/api/demo/notify?userId=bench&message=test", 1000, 8));

        // === 报告 ===
        System.out.println("\n" + "=".repeat(70));
        System.out.println(BenchReporter.report(results,
                "RPC layer | " + BASE_URL + " | JDK " + System.getProperty("java.version")
                        + " | " + Runtime.getRuntime().availableProcessors() + " cores"));
    }

    static boolean healthCheck() {
        try {
            HttpResponse<String> resp = CLIENT.send(
                    HttpRequest.newBuilder(URI.create(BASE_URL + "/api/demo/id")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    static BenchResult benchGet(String scenario, String path, int totalReqs, int concurrency) throws Exception {
        return bench(scenario, "GET", path, totalReqs, concurrency);
    }

    static BenchResult benchPost(String scenario, String path, int totalReqs, int concurrency) throws Exception {
        return bench(scenario, "POST", path, totalReqs, concurrency);
    }

    static BenchResult bench(String scenario, String method, String path, int totalReqs, int concurrency)
            throws Exception {
        System.out.printf("  Running %-45s (%d reqs, %d threads)...", scenario, totalReqs, concurrency);
        System.out.flush();

        Histogram hist = new Histogram(3);
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);
        CountDownLatch done = new CountDownLatch(totalReqs);
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        long start = System.nanoTime();

        for (int i = 0; i < totalReqs; i++) {
            pool.submit(() -> {
                try {
                    long t0 = System.nanoTime();
                    HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(BASE_URL + path));
                    if ("POST".equals(method)) {
                        reqBuilder.POST(HttpRequest.BodyPublishers.ofString(""));
                    } else {
                        reqBuilder.GET();
                    }
                    HttpResponse<String> resp = CLIENT.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                    long t1 = System.nanoTime();
                    if (resp.statusCode() == 200) {
                        synchronized (hist) { hist.recordValue(t1 - t0); }
                        completed.incrementAndGet();
                    } else {
                        errors.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        done.await();
        long elapsed = System.nanoTime() - start;
        pool.shutdown();
        pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

        double opsPerSec = completed.get() / (elapsed / 1_000_000_000.0);
        System.out.printf(" done (%.0f ops/s, %d errors)%n", opsPerSec, errors.get());

        BenchResult r = new BenchResult(scenario, "rpc", concurrency,
                completed.get(), opsPerSec,
                hist.getValueAtPercentile(50), hist.getValueAtPercentile(95),
                hist.getValueAtPercentile(99), hist.getValueAtPercentile(99.9),
                hist.getMaxValue(), method + " " + path);
        r.pass(completed.get() + "/" + totalReqs + " succeeded");
        if (errors.get() > 0) r.fail(errors.get() + " errors");
        System.out.println("    " + r);
        return r;
    }
}
