package com.example.virtualthreads.traditional;

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
 * Traditional platform threads approach to handling concurrent HTTP requests.
 * Demonstrates the limitations of fixed thread pools.
 */
public class PlatformThreadsExample {

    private static final int TOTAL_REQUESTS = 10_000;
    private static final int THREAD_POOL_SIZE = 200;
    private static final String TARGET_URL = "https://httpbin.org/delay/1";

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        System.out.println("=== Platform Threads Example ===");
        System.out.println("Simulating " + TOTAL_REQUESTS + " concurrent HTTP requests");
        System.out.println("Thread pool size: " + THREAD_POOL_SIZE);
        
        var start = Instant.now();
        
        // Traditional fixed thread pool - limited by pool size
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        
        List<Future<Integer>> futures = new ArrayList<>();
        
        // Submit all tasks
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
                        System.out.println("Request " + requestId + " completed: " + response.statusCode());
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
        
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.MINUTES);
        
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
        
        System.out.println("\nLimitation: With " + THREAD_POOL_SIZE + " threads, we can only process " 
                + THREAD_POOL_SIZE + " requests concurrently.");
        System.out.println("Remaining requests queue up, increasing overall latency.");
    }
}
