package com.example.virtualthreads.patterns;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates the ThreadLocal memory bloat issue with virtual threads.
 * Shows the problem and the solution.
 */
public class ThreadLocalPattern {

    // ❌ BAD: ThreadLocal cache multiplies by thread count
    private static final ThreadLocal<Map<String, String>> BAD_CACHE = 
            ThreadLocal.withInitial(HashMap::new);

    // ✅ GOOD: Request-scoped cache passed as parameter
    static class RequestContext {
        private final Map<String, String> cache = new HashMap<>();
        
        public void put(String key, String value) {
            cache.put(key, value);
        }
        
        public String get(String key) {
            return cache.get(key);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== ThreadLocal Anti-Pattern Demo ===\n");
        
        // Demonstrate the problem
        System.out.println("1. Running with BAD ThreadLocal pattern (platform threads):");
        runWithBadPattern(Executors.newFixedThreadPool(200), 1000);
        
        System.gc();
        Thread.sleep(1000);
        
        System.out.println("\n2. Running with BAD ThreadLocal pattern (virtual threads):");
        runWithBadPattern(Executors.newVirtualThreadPerTaskExecutor(), 10000);
        
        System.gc();
        Thread.sleep(1000);
        
        System.out.println("\n3. Running with GOOD pattern (virtual threads):");
        runWithGoodPattern(Executors.newVirtualThreadPerTaskExecutor(), 10000);
    }

    private static void runWithBadPattern(ExecutorService executor, int taskCount) 
            throws InterruptedException {
        long startMemory = getUsedMemory();
        
        for (int i = 0; i < taskCount; i++) {
            final int taskId = i;
            executor.submit(() -> {
                // Each thread gets its own cache via ThreadLocal
                Map<String, String> cache = BAD_CACHE.get();
                
                // Simulate caching user data
                cache.put("user_" + taskId, "data_" + taskId);
                cache.put("session_" + taskId, "session_data_" + taskId);
                
                // Do some work
                simulateWork();
            });
        }
        
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);
        
        long endMemory = getUsedMemory();
        System.out.println("Tasks executed: " + taskCount);
        System.out.println("Memory used: " + (endMemory - startMemory) + " MB");
        System.out.println("Memory per task: " + 
                ((endMemory - startMemory) * 1024.0 / taskCount) + " KB");
    }

    private static void runWithGoodPattern(ExecutorService executor, int taskCount) 
            throws InterruptedException {
        long startMemory = getUsedMemory();
        
        for (int i = 0; i < taskCount; i++) {
            final int taskId = i;
            executor.submit(() -> {
                // Create request-scoped context
                RequestContext context = new RequestContext();
                
                // Use the context
                context.put("user_" + taskId, "data_" + taskId);
                context.put("session_" + taskId, "session_data_" + taskId);
                
                // Do some work
                simulateWork();
                
                // Context is garbage collected after task completes
            });
        }
        
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);
        
        long endMemory = getUsedMemory();
        System.out.println("Tasks executed: " + taskCount);
        System.out.println("Memory used: " + (endMemory - startMemory) + " MB");
        System.out.println("Memory per task: " + 
                ((endMemory - startMemory) * 1024.0 / taskCount) + " KB");
    }

    private static void simulateWork() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    }
}
