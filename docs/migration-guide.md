# Virtual Threads Migration Guide

A step-by-step guide for migrating your Java application to virtual threads based on real production experience.

## Prerequisites

- Java 21 or higher
- Spring Boot 3.2+ (if using Spring)
- Understanding of your application's threading patterns

## Phase 1: Assessment (Week 1-2)

### 1.1 Audit Your Codebase

Run these searches to identify potential issues:

```bash
# Find ThreadLocal usage
grep -r "ThreadLocal" src/

# Find synchronized blocks
grep -r "synchronized" src/

# Find explicit thread pools
grep -r "Executors.newFixedThreadPool\|Executors.newCachedThreadPool" src/

# Find thread creation
grep -r "new Thread" src/
```

### 1.2 Identify Critical Services

Start with services that are:
- **I/O-bound** (database queries, REST calls, file operations)
- **High throughput** (handles many concurrent requests)
- **Non-critical** (can tolerate issues during testing)

Avoid starting with:
- CPU-intensive services
- Services with heavy synchronized usage
- Mission-critical services

### 1.3 Measure Baseline Metrics

Capture these metrics before migration:

- Request throughput (RPS)
- P50, P95, P99 latency
- Memory usage per instance
- CPU utilization
- Thread pool utilization
- Number of pods/containers required

## Phase 2: Preparation (Week 3)

### 2.1 Fix ThreadLocal Issues

**Problem:** ThreadLocal multiplies memory by thread count.

```java
// BAD - Will cause memory bloat
private static final ThreadLocal<ExpensiveObject> cache = new ThreadLocal<>();

// GOOD - Use request-scoped beans
@RequestScope
public class RequestContext {
    private final Map<String, Object> attributes = new HashMap<>();
    // ...
}
```

**Action items:**
- Replace ThreadLocal caches with request-scoped beans
- Remove ThreadLocal-based MDC if using custom logging
- Audit third-party libraries for ThreadLocal usage

### 2.2 Replace Synchronized Blocks

**Problem:** Synchronized blocks pin carrier threads.

```java
// BAD - Pins carrier thread
synchronized (lock) {
    updateCache(key, value);
}

// GOOD - Allows unmounting
private final ReentrantLock lock = new ReentrantLock();

lock.lock();
try {
    updateCache(key, value);
} finally {
    lock.unlock();
}
```

**Priority for replacement:**
- High-contention locks in critical paths
- Long-held locks (>10ms)
- Locks in frequently-called methods

### 2.3 Increase Connection Pool Sizes

Virtual threads can support much higher concurrency:

```properties
# Before (platform threads)
spring.datasource.hikari.maximum-pool-size=20

# After (virtual threads)
spring.datasource.hikari.maximum-pool-size=50
```

**Guidelines:**
- Start with 2.5-3x your previous pool size
- Monitor connection usage
- Adjust based on database capacity

### 2.4 Set Up Monitoring

Add metrics for virtual threads:

```java
@Bean
public MeterBinder virtualThreadMetrics() {
    return (registry) -> {
        Gauge.builder("jdk.virtualThreads.pinned", this::getPinnedCount)
            .description("Number of pinned virtual threads")
            .register(registry);
    };
}
```

Essential metrics:
- `jdk.virtualThreads.pinned.count`
- `jdk.virtualThreads.created.total`
- `jdk.platformThreads.carrier.utilization`

## Phase 3: Migration (Week 4-5)

### 3.1 Enable Virtual Threads (Spring Boot)

**Single property change:**

```properties
spring.threads.virtual.enabled=true
```

That's it! Spring Boot handles the rest.

### 3.2 For Non-Spring Applications

Replace thread pools:

```java
// Before
ExecutorService executor = Executors.newFixedThreadPool(200);

// After
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
```

### 3.3 Canary Deployment

**Week 4:** Deploy to 10% of traffic
- Monitor for 48 hours
- Check for memory leaks
- Watch for carrier thread pinning
- Compare latency distributions

**Week 5:** Gradually increase
- 25% → 50% → 100%
- Roll back immediately if issues detected
- Compare metrics at each stage

## Phase 4: Optimization (Week 6-8)

### 4.1 Tune Connection Pools

Based on monitoring, adjust:

```properties
# Database
spring.datasource.hikari.maximum-pool-size=75

# HTTP Client
http.client.max-connections=100
```

### 4.2 Remove Unnecessary Thread Pools

Find and remove custom thread pools:

```java
// Before - unnecessary with virtual threads
@Bean
public ExecutorService taskExecutor() {
    return Executors.newFixedThreadPool(50);
}

// After - use virtual threads directly
public void processAsync(Task task) {
    Thread.startVirtualThread(() -> process(task));
}
```

### 4.3 Optimize for Carrier Thread Pinning

If you see high pinning counts:

1. **Identify the culprit:**
```bash
jcmd <pid> Thread.dump_to_file -format=json threads.json
# Look for threads in PINNED state
```

2. **Common fixes:**
   - Replace `synchronized` with `ReentrantLock`
   - Avoid JNI calls on virtual threads
   - Check third-party libraries

## Phase 5: Validation (Week 9-10)

### 5.1 Compare Metrics

Create a comparison table:

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| P95 Latency | 145ms | 89ms | 39% |
| Throughput | 1000 RPS | 1600 RPS | 60% |
| Memory | 2.1 GB | 1.3 GB | 40% |
| Pods Required | 12 | 7 | 42% |

### 5.2 Load Testing

Run realistic load tests:

```bash
# Use your load testing tool
wrk -t 12 -c 400 -d 30s http://localhost:8080/api/users/1

# Or Apache Bench
ab -n 10000 -c 200 http://localhost:8080/api/users/1
```

### 5.3 Soak Testing

Run for extended periods:
- 24 hours minimum
- Monitor for memory leaks
- Watch GC behavior
- Check for ThreadLocal bloat

## Common Issues and Solutions

### Issue 1: Memory Leak from ThreadLocal

**Symptoms:**
- Memory grows over time
- GC can't reclaim memory
- OutOfMemoryError after hours

**Solution:**
```java
// Add cleanup in filter or aspect
ThreadLocal.remove(); // Call after request completes
```

### Issue 2: Carrier Thread Exhaustion

**Symptoms:**
- High `jdk.virtualThreads.pinned` count
- Degraded throughput
- Increased latency

**Solution:**
- Replace synchronized with Lock APIs
- Increase carrier thread pool (last resort):
  ```bash
  -Djdk.virtualThreadScheduler.parallelism=32
  ```

### Issue 3: Database Connection Pool Exhaustion

**Symptoms:**
- "Cannot get connection" errors
- Timeout exceptions
- High wait times for connections

**Solution:**
```properties
# Increase pool size gradually
spring.datasource.hikari.maximum-pool-size=50
# Add connection timeout
spring.datasource.hikari.connection-timeout=20000
```

## Rollback Plan

If issues arise:

1. **Immediate rollback:**
   ```properties
   spring.threads.virtual.enabled=false
   ```

2. **Redeploy previous version**

3. **Analyze metrics** to understand what went wrong

4. **Fix issues** before retry

## Success Criteria

Migration is successful when:

- [ ] Latency improves or stays the same
- [ ] Throughput increases
- [ ] Memory usage decreases or stays stable
- [ ] No increase in error rates
- [ ] CPU utilization improves
- [ ] Can reduce container count
- [ ] No carrier thread pinning issues
- [ ] Stable under load for 7 days

## Timeline Summary

| Phase | Duration | Key Activities |
|-------|----------|----------------|
| Assessment | 2 weeks | Audit code, identify services |
| Preparation | 1 week | Fix ThreadLocal, add monitoring |
| Migration | 2 weeks | Canary deployment, gradual rollout |
| Optimization | 3 weeks | Tune pools, fix pinning |
| Validation | 2 weeks | Load test, soak test |
| **Total** | **10 weeks** | For single service |

**For 10,000 services:** Plan 18-24 months with parallel migration of service groups.

## Additional Resources

- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444)
- [Spring Boot Virtual Threads Guide](https://spring.io/blog/2023/09/09/virtual-threads)
- [Monitoring with JFR](docs/monitoring.md)

## Questions?

Check our [GitHub Issues](https://github.com/dinesh-k-elumalai/virtual-threads-example/issues) or the [FAQ](docs/faq.md).
