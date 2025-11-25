package com.example.virtualthreads.virtual;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

/**
 * Spring Boot application demonstrating virtual threads.
 * 
 * Configuration:
 * - Add to application.properties: spring.threads.virtual.enabled=true
 * - Spring Boot 3.2+ automatically uses virtual threads for request handling
 */
@SpringBootApplication
@EnableAsync
public class VirtualThreadsSpringBootApp {

    public static void main(String[] args) {
        SpringApplication.run(VirtualThreadsSpringBootApp.class, args);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * Virtual thread executor for async operations
     */
    @Bean(name = "virtualThreadExecutor")
    public Executor virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

@RestController
@RequestMapping("/api")
class UserController {

    @SuppressWarnings("unused")
    private final RestTemplate restTemplate;
    private final Executor virtualThreadExecutor;

    public UserController(RestTemplate restTemplate, 
                         @org.springframework.beans.factory.annotation.Qualifier("virtualThreadExecutor") 
                         Executor virtualThreadExecutor) {
        this.restTemplate = restTemplate;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    /**
     * Simple blocking endpoint - scales beautifully with virtual threads
     */
    @GetMapping("/users/{id}")
    public UserResponse getUser(@PathVariable Long id) {
        var start = Instant.now();
        
        // All these blocking calls run on a virtual thread
        var userDetails = fetchUserDetails(id);
        var orders = fetchOrders(id);
        var preferences = fetchPreferences(id);
        
        var duration = Duration.between(start, Instant.now());
        
        return new UserResponse(
            userDetails,
            orders,
            preferences,
            duration.toMillis(),
            Thread.currentThread().toString()
        );
    }

    /**
     * Parallel service calls using virtual threads
     */
    @GetMapping("/users/batch")
    public List<UserSummary> getBatchUsers(@RequestParam List<Long> ids) {
        var start = Instant.now();
        
        // Each parallel task runs on its own virtual thread
        List<UserSummary> results = ids.parallelStream()
            .map(this::fetchUserSummary)
            .toList();
        
        var duration = Duration.between(start, Instant.now());
        System.out.println("Fetched " + ids.size() + " users in " + 
                          duration.toMillis() + "ms");
        
        return results;
    }

    /**
     * Async processing with virtual threads
     */
    @PostMapping("/users/{id}/process")
    public CompletableFuture<ProcessResult> processUserAsync(@PathVariable Long id) {
        return CompletableFuture.supplyAsync(() -> {
            // Long-running processing on virtual thread
            var user = fetchUserDetails(id);
            
            // Simulate heavy processing
            IntStream.range(0, 10)
                .forEach(i -> {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            
            return new ProcessResult(id, "completed", user);
        }, virtualThreadExecutor);
    }

    // Simulated service calls
    private String fetchUserDetails(Long id) {
        simulateNetworkCall(50);
        return "User details for " + id;
    }

    private String fetchOrders(Long id) {
        simulateNetworkCall(100);
        return "Orders for user " + id;
    }

    private String fetchPreferences(Long id) {
        simulateNetworkCall(30);
        return "Preferences for user " + id;
    }

    private UserSummary fetchUserSummary(Long id) {
        simulateNetworkCall(50);
        return new UserSummary(id, "User " + id);
    }

    private void simulateNetworkCall(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

record UserResponse(
    String userDetails,
    String orders,
    String preferences,
    long latencyMs,
    String threadInfo
) {}

record UserSummary(Long id, String name) {}

record ProcessResult(Long userId, String status, String data) {}
