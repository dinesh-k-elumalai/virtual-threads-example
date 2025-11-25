package com.example.virtualthreads.patterns;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Demonstrates carrier thread pinning with synchronized blocks
 * and the solution using ReentrantLock.
 */
public class SynchronizedBlockPinning {

    private static final Object syncLock = new Object();
    private static final ReentrantLock reentrantLock = new ReentrantLock();
    private static int sharedCounter = 0;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Synchronized Block Pinning Demo ===\n");
        
        int taskCount = 10_000;
        
        System.out.println("1. Using synchronized blocks (pins carrier threads):");
        runWithSynchronized(taskCount);
        
        sharedCounter = 0; // Reset
        
        System.out.println("\n2. Using ReentrantLock (unmounts properly):");
        runWithReentrantLock(taskCount);
        
        System.out.println("\n=== Explanation ===");
        System.out.println("synchronized blocks prevent virtual threads from unmounting");
        System.out.println("from carrier threads, causing blocking and reduced throughput.");
        System.out.println("\nReentrantLock allows virtual threads to unmount during waits,");
        System.out.println("freeing up carrier threads for other virtual threads.");
    }

    private static void runWithSynchronized(int taskCount) throws InterruptedException {
        var start = Instant.now();
        
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < taskCount; i++) {
                executor.submit(() -> {
                    synchronized (syncLock) {
                        // This pins the carrier thread!
                        sharedCounter++;
                        simulateWork();
                    }
                });
            }
        }
        
        var duration = Duration.between(start, Instant.now());
        System.out.println("Time taken: " + duration.toMillis() + " ms");
        System.out.println("Final counter: " + sharedCounter);
        System.out.println("Throughput: " + (taskCount * 1000.0 / duration.toMillis()) + " tasks/sec");
    }

    private static void runWithReentrantLock(int taskCount) throws InterruptedException {
        var start = Instant.now();
        
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < taskCount; i++) {
                executor.submit(() -> {
                    reentrantLock.lock();
                    try {
                        // Virtual thread can unmount here if needed
                        sharedCounter++;
                        simulateWork();
                    } finally {
                        reentrantLock.unlock();
                    }
                });
            }
        }
        
        var duration = Duration.between(start, Instant.now());
        System.out.println("Time taken: " + duration.toMillis() + " ms");
        System.out.println("Final counter: " + sharedCounter);
        System.out.println("Throughput: " + (taskCount * 1000.0 / duration.toMillis()) + " tasks/sec");
    }

    private static void simulateWork() {
        try {
            Thread.sleep(1); // Simulate some work
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
