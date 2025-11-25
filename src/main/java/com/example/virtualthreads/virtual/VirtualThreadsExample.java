package com.example.virtualthreads.virtual;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Virtual threads approach - scales to workload without fixed thread limits.
 * Demonstrates the power of Project Loom for I/O-bound operations.
 */
public class VirtualThreadsExample {

    private static final int TOTAL_REQUESTS = 10_000;
    private static final String TARGET_URL = "https://httpbin.org/delay/1";

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        System.out.println("=== Virtual Threads Example ===");
        System.out.println("Simulating " + TOTAL_REQUESTS + " concurrent HTTP requests");
        System.out.println("Using virtual threads - no fixed pool size");
        
        var start = Instant.now();
        
        // Virtual thread per task executor - scales to workload
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            
            List<Future<Integer>> futures = new ArrayList<>();
            
            // Submit all tasks - each gets its own virtual thread
            for (int i = 0; i < TOTAL_REQUESTS; i++) {
                final int requestId = i;
                Future<Integer> future = executor.submit(() -> {
                    try {
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(TARGET_URL))
                                .timeout(Duration.ofSeconds(10))
                                .GET()
                                .build();
                        
                        HttpResponse<String> response = client.send(
                                request, 
                                HttpResponse.BodyHandlers.ofString()
                        );
                        
                        if (requestId % 1000 == 0) {
                            System.out.println("Request " + requestId + " completed: " + 
                                    response.statusCode() + " (Thread: " + 
                                    Thread.currentThread() + ")");
                        }
                        
                        return response.statusCode();
                    } catch (Exception e) {
                        System.err.println("Request " + requestId + " failed: " + e.getMessage());
                        return -1;
                    }
                });
                futures.add(future);
            }
            
            // Wait for all to complete
            int successCount = 0;
            int failureCount = 0;
            
            for (Future<Integer> future : futures) {
                try {
                    int statusCode = future.get();
                    if (statusCode == 200) {
                        successCount++;
                    } else {
                        failureCount++;
                    }
                } catch (Exception e) {
                    failureCount++;
                }
            }
            
            var end = Instant.now();
            var duration = Duration.between(start, end);
            
            System.out.println("\n=== Results ===");
            System.out.println("Total time: " + duration.toSeconds() + " seconds");
            System.out.println("Successful requests: " + successCount);
            System.out.println("Failed requests: " + failureCount);
            System.out.println("Throughput: " + (TOTAL_REQUESTS / duration.toSeconds()) + " req/sec");
            System.out.println("Average latency: " + (duration.toMillis() / TOTAL_REQUESTS) + " ms");
            
            // Memory usage
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
            System.out.println("Memory used: " + usedMemory + " MB");
            
            System.out.println("\nAdvantage: All " + TOTAL_REQUESTS + " requests run concurrently!");
            System.out.println("Virtual threads are cheap - we can create millions of them.");
            System.out.println("Much lower memory footprint than platform threads.");
        }
    }
}
