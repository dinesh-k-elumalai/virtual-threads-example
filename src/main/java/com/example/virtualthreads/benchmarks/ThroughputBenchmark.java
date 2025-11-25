package com.example.virtualthreads.benchmarks;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Comprehensive throughput benchmark comparing platform threads vs virtual threads.
 * Measures: latency, throughput, memory usage, and scalability.
 */
public class ThroughputBenchmark {

    private static final int WARMUP_ITERATIONS = 1000;
    private static final int BENCHMARK_ITERATIONS = 10_000;
    private static final int SIMULATED_IO_DELAY_MS = 100;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Virtual Threads Throughput Benchmark ===\n");
        
        // Warmup
        System.out.println("Warming up JVM...");
        runBenchmark(Executors.newFixedThreadPool(10), WARMUP_ITERATIONS, false);
        runBenchmark(Executors.newVirtualThreadPerTaskExecutor(), WARMUP_ITERATIONS, false);
        System.gc();
        Thread.sleep(2000);
        
        // Benchmark different scenarios
        System.out.println("\n=== Scenario 1: Low Concurrency (100 threads) ===");
        benchmarkScenario(100, 1000);
        
        System.out.println("\n=== Scenario 2: Medium Concurrency (1,000 threads) ===");
        benchmarkScenario(200, 5000);
        
        System.out.println("\n=== Scenario 3: High Concurrency (10,000 threads) ===");
        benchmarkScenario(200, BENCHMARK_ITERATIONS);
        
        System.out.println("\n=== Benchmark Complete ===");
    }

    private static void benchmarkScenario(int platformThreads, int tasks) 
            throws Exception {
        
        // Platform threads
        System.out.println("\n1. Platform Threads (" + platformThreads + " threads):");
        ExecutorService platformExecutor = Executors.newFixedThreadPool(platformThreads);
        BenchmarkResult platformResult = runBenchmark(platformExecutor, tasks, true);
        platformExecutor.shutdown();
        platformExecutor.awaitTermination(5, TimeUnit.MINUTES);
        
        System.gc();
        Thread.sleep(1000);
        
        // Virtual threads
        System.out.println("\n2. Virtual Threads (unlimited):");
        ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        BenchmarkResult virtualResult = runBenchmark(virtualExecutor, tasks, true);
        virtualExecutor.close();
        
        // Comparison
        System.out.println("\n=== Comparison ===");
        printComparison(platformResult, virtualResult);
    }

    private static BenchmarkResult runBenchmark(
            ExecutorService executor, 
            int taskCount, 
            boolean printResults) throws Exception {
        
        long startMemory = getUsedMemory();
        Instant start = Instant.now();
        
        List<Future<TaskResult>> futures = new ArrayList<>();
        
        // Submit all tasks
        for (int i = 0; i < taskCount; i++) {
            final int taskId = i;
            Future<TaskResult> future = executor.submit(() -> executeTask(taskId));
            futures.add(future);
        }
        
        // Collect results
        List<TaskResult> results = new ArrayList<>();
        for (Future<TaskResult> future : futures) {
            results.add(future.get());
        }
        
        Instant end = Instant.now();
        long endMemory = getUsedMemory();
        
        Duration totalDuration = Duration.between(start, end);
        
        // Calculate statistics
        long totalLatency = results.stream()
                .mapToLong(TaskResult::latencyMs)
                .sum();
        long avgLatency = totalLatency / taskCount;
        
        List<Long> sortedLatencies = results.stream()
                .map(TaskResult::latencyMs)
                .sorted()
                .toList();
        long p50 = sortedLatencies.get(taskCount / 2);
        long p95 = sortedLatencies.get((int) (taskCount * 0.95));
        long p99 = sortedLatencies.get((int) (taskCount * 0.99));
        
        double throughput = taskCount / (double) totalDuration.toSeconds();
        long memoryUsed = endMemory - startMemory;
        
        BenchmarkResult result = new BenchmarkResult(
                totalDuration.toMillis(),
                throughput,
                avgLatency,
                p50,
                p95,
                p99,
                memoryUsed,
                taskCount
        );
        
        if (printResults) {
            printResult(result);
        }
        
        return result;
    }

    private static TaskResult executeTask(int taskId) {
        Instant start = Instant.now();
        
        // Simulate I/O-bound work
        try {
            Thread.sleep(SIMULATED_IO_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TaskResult(taskId, -1);
        }
        
        Instant end = Instant.now();
        long latency = Duration.between(start, end).toMillis();
        
        return new TaskResult(taskId, latency);
    }

    private static void printResult(BenchmarkResult result) {
        System.out.println("Total time: " + result.totalTimeMs + " ms");
        System.out.println("Throughput: " + String.format("%.2f", result.throughput) + " tasks/sec");
        System.out.println("Latency (avg): " + result.avgLatencyMs + " ms");
        System.out.println("Latency (p50): " + result.p50LatencyMs + " ms");
        System.out.println("Latency (p95): " + result.p95LatencyMs + " ms");
        System.out.println("Latency (p99): " + result.p99LatencyMs + " ms");
        System.out.println("Memory used: " + result.memoryUsedMB + " MB");
        System.out.println("Memory per task: " + 
                String.format("%.2f", result.memoryUsedMB * 1024.0 / result.taskCount) + " KB");
    }

    private static void printComparison(BenchmarkResult platform, BenchmarkResult virtual) {
        double timeImprovement = ((platform.totalTimeMs - virtual.totalTimeMs) / 
                (double) platform.totalTimeMs) * 100;
        double throughputImprovement = ((virtual.throughput - platform.throughput) / 
                platform.throughput) * 100;
        double latencyImprovement = ((platform.avgLatencyMs - virtual.avgLatencyMs) / 
                (double) platform.avgLatencyMs) * 100;
        double memoryImprovement = ((platform.memoryUsedMB - virtual.memoryUsedMB) / 
                (double) platform.memoryUsedMB) * 100;
        
        System.out.println("Time improvement: " + 
                String.format("%.1f%%", timeImprovement) + 
                (timeImprovement > 0 ? " faster" : " slower"));
        System.out.println("Throughput improvement: " + 
                String.format("%.1f%%", throughputImprovement) + 
                (throughputImprovement > 0 ? " higher" : " lower"));
        System.out.println("Latency improvement: " + 
                String.format("%.1f%%", latencyImprovement) + 
                (latencyImprovement > 0 ? " better" : " worse"));
        System.out.println("Memory improvement: " + 
                String.format("%.1f%%", memoryImprovement) + 
                (memoryImprovement > 0 ? " less" : " more"));
    }

    private static long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    }

    record TaskResult(int taskId, long latencyMs) {}

    record BenchmarkResult(
            long totalTimeMs,
            double throughput,
            long avgLatencyMs,
            long p50LatencyMs,
            long p95LatencyMs,
            long p99LatencyMs,
            long memoryUsedMB,
            int taskCount
    ) {}
}
