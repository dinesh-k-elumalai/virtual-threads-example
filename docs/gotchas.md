# Virtual Threads Gotchas: Common Pitfalls and How to Avoid Them

A compilation of real production issues encountered during virtual threads migration and their solutions.

## Table of Contents
1. [ThreadLocal Memory Bloat](#threadlocal-memory-bloat)
2. [Carrier Thread Pinning](#carrier-thread-pinning)
3. [Synchronized Blocks](#synchronized-blocks)
4. [Connection Pool Sizing](#connection-pool-sizing)
5. [Thread Dumps Become Useless](#thread-dumps-become-useless)
6. [JNI and Native Code](#jni-and-native-code)
7. [Thread Pool Misconceptions](#thread-pool-misconceptions)
8. [Monitoring and Observability](#monitoring-and-observability)
9. [Framework Assumptions](#framework-assumptions)
10. [Performance Testing Pitfalls](#performance-testing-pitfalls)

---

## ThreadLocal Memory Bloat

### The Problem

ThreadLocal variables multiply by thread count. With platform threads (200), this was manageable. With millions of virtual threads, it becomes a memory disaster.

### Real Example

```java
// This innocent-looking cache became a 3x memory bloat
private static final ThreadLocal<Map<String, UserData>> USER_CACHE = 
    ThreadLocal.withInitial(HashMap::new);

public void processRequest(String userId) {
    Map<String, UserData> cache = USER_CACHE.get();
    cache.put(userId, fetchUserData(userId));
    // This cache never gets cleaned up!
}
```

### What Happened

- Platform threads: 200 threads × 10KB cache = 2MB total
- Virtual threads: 100,000 threads × 10KB cache = 1GB total
- Memory usage grew 500x with the same workload

### The Fix

**Option 1: Request-Scoped Beans (Spring)**
```java
@RequestScope
@Component
public class RequestContext {
    private final Map<String, UserData> cache = new HashMap<>();
    
    public void cacheUser(String userId, UserData data) {
        cache.put(userId, data);
    }
}
```

**Option 2: Pass Context Explicitly**
```java
public class RequestContext {
    private final Map<String, UserData> cache = new HashMap<>();
}

public void processRequest(String userId) {
    RequestContext context = new RequestContext();
    handleRequest(userId, context);
    // Context is garbage collected after method returns
}
```

**Option 3: ScopedValue (Java 21+)**
```java
private static final ScopedValue<Map<String, UserData>> CACHE = 
    ScopedValue.newInstance();

public void processRequest(String userId) {
    ScopedValue.where(CACHE, new HashMap<>())
        .run(() -> handleRequest(userId));
    // Automatically cleaned up
}
```

### Detection

```bash
# Monitor memory growth
jcmd <pid> GC.heap_info

# Check ThreadLocal usage
grep -r "ThreadLocal" src/

# Use JFR to track allocations
jcmd <pid> JFR.start settings=profile
```

---

## Carrier Thread Pinning

### The Problem

Virtual threads run on carrier threads (platform threads). When a virtual thread "pins" the carrier, it blocks that carrier from running other virtual threads, defeating the whole purpose.

### What Causes Pinning

1. **Synchronized blocks**
2. **Native methods (JNI calls)**
3. **Some blocking operations in the JDK**

### Real Example

```java
// This synchronized block pinned carrier threads for 50ms each
private final Object lock = new Object();

public void updateCache(String key, String value) {
    synchronized (lock) {
        // This takes 50ms due to disk I/O
        cache.put(key, value);
        persistToDisk(key, value);
    }
}
```

### Impact

- 8 carrier threads (core count)
- Each pinned for 50ms
- Maximum throughput: 8 × (1000ms / 50ms) = 160 requests/second
- With platform threads, we were handling 1,000+ RPS

### The Fix

```java
private final ReentrantLock lock = new ReentrantLock();

public void updateCache(String key, String value) {
    lock.lock();
    try {
        cache.put(key, value);
        persistToDisk(key, value);
        // Virtual thread can unmount during I/O
    } finally {
        lock.unlock();
    }
}
```

### Detection

**JFR Events:**
```bash
jcmd <pid> JFR.start name=pinning settings=profile
# Look for jdk.VirtualThreadPinned events
```

**Custom Metrics:**
```java
@Bean
public MeterBinder carrierThreadMetrics() {
    return registry -> {
        Gauge.builder("jvm.threads.virtual.pinned", 
            this::getVirtualThreadsPinned)
            .register(registry);
    };
}
```

### Quick Audit

```bash
# Find all synchronized blocks
grep -rn "synchronized" src/ | grep -v "^Binary"

# Check for synchronized methods
grep -rn "synchronized.*{" src/
```

---

## Synchronized Blocks

### The Problem

Not just pinning - synchronized blocks prevent virtual threads from being preempted or unmounted, even for non-blocking operations.

### Bad Patterns

```java
// Pattern 1: Synchronized method
public synchronized void increment() {
    counter++;
}

// Pattern 2: Synchronized on this
public void process() {
    synchronized (this) {
        doWork();
    }
}

// Pattern 3: Synchronized on class
synchronized (MyClass.class) {
    updateStaticState();
}
```

### Good Alternatives

```java
// Use ReentrantLock
private final ReentrantLock lock = new ReentrantLock();

public void increment() {
    lock.lock();
    try {
        counter++;
    } finally {
        lock.unlock();
    }
}

// Use Atomic classes for simple cases
private final AtomicInteger counter = new AtomicInteger();

public void increment() {
    counter.incrementAndGet();
}

// Use concurrent collections
private final ConcurrentHashMap<String, Value> cache = new ConcurrentHashMap<>();

public void put(String key, Value value) {
    cache.put(key, value); // No locking needed
}
```

### Migration Priority

Replace synchronized blocks in this order:
1. **High-frequency methods** (called millions of times)
2. **Long-held locks** (>10ms)
3. **Hot paths** (in request handling)
4. **Contended locks** (many threads waiting)

---

## Connection Pool Sizing

### The Problem

Connection pools sized for 200 platform threads are too small for virtual threads.

### What We Had

```properties
# Sized for platform threads
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
```

Result: Connection pool exhaustion with virtual threads
- Error: "Connection is not available, request timed out after 30000ms"
- Throughput dropped 80%

### The Fix

```properties
# Sized for virtual threads
spring.datasource.hikari.maximum-pool-size=50
spring.datasource.hikari.minimum-idle=10
spring.datasource.hikari.connection-timeout=20000
spring.datasource.hikari.leak-detection-threshold=60000
```

### Guidelines

| Pool Type | Platform Threads | Virtual Threads | Multiplier |
|-----------|-----------------|-----------------|------------|
| Database | 20 | 50-75 | 2.5-3x |
| HTTP Client | 200 | 100-150 | 0.5x |
| Redis | 10 | 25-30 | 2.5-3x |
| Kafka | 10 | 10-20 | 1-2x |

### Why Different?

- **Database**: Virtual threads can wait for connections without blocking carriers
- **HTTP Client**: Built-in connection management works well with virtual threads
- **Redis**: Similar to database, benefit from larger pools
- **Kafka**: Consumer threads are long-lived, less benefit from virtual threads

### Tuning Process

1. **Start with 2.5x your platform thread pool size**
2. **Load test and monitor**:
   ```sql
   -- Check active connections
   SELECT count(*) FROM pg_stat_activity WHERE state = 'active';
   ```
3. **Increase gradually** if you see:
   - Connection timeouts
   - High connection wait times
   - Pool exhaustion errors
4. **Stop before** you overload the database

---

## Thread Dumps Become Useless

### The Problem

Thread dumps with 2 million virtual threads are impossible to analyze.

```bash
# Traditional approach
jstack <pid> > thread_dump.txt

# Result: 500MB file with 2 million thread entries
```

### What Doesn't Work

- Traditional thread dump tools (jstack, kill -3)
- Thread dump analyzers (they crash on large files)
- Searching for blocked threads (too many results)

### What Works

**1. JFR (Java Flight Recorder)**
```bash
jcmd <pid> JFR.start name=threads settings=profile
# Wait 30 seconds
jcmd <pid> JFR.dump name=threads filename=threads.jfr

# Analyze with JMC or command line
jfr print --events jdk.VirtualThreadPinned threads.jfr
jfr print --events jdk.VirtualThreadSubmitFailed threads.jfr
```

**2. Structured Logging with Correlation IDs**
```java
// Add correlation ID at request entry
@WebFilter
public class CorrelationIdFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, 
                        FilterChain chain) {
        String correlationId = UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("correlationId");
        }
    }
}

// Now you can trace requests across threads
logger.info("Processing order {}", orderId);
// Log: [correlationId=abc-123] Processing order 456
```

**3. Distributed Tracing (OpenTelemetry)**
```java
@Bean
public OpenTelemetry openTelemetry() {
    return OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .setPropagators(ContextPropagators.create(
            W3CTraceContextPropagator.getInstance()))
        .build();
}
```

**4. Custom Metrics for Debugging**
```java
@Component
public class VirtualThreadMetrics {
    private final Counter threadsCreated;
    private final Gauge threadsPinned;
    
    public VirtualThreadMetrics(MeterRegistry registry) {
        this.threadsCreated = registry.counter("vthreads.created");
        this.threadsPinned = Gauge.builder("vthreads.pinned", 
            this::getPinnedCount).register(registry);
    }
}
```

---

## JNI and Native Code

### The Problem

Native methods pin carrier threads. Always.

### Example

```java
// This native method pins the carrier thread
private native void processImageNative(byte[] imageData);

public void processImage(byte[] data) {
    processImageNative(data); // Pins carrier thread for entire operation
}
```

### Impact

If processing takes 100ms and you have 8 cores:
- Maximum throughput: 80 requests/second
- With virtual threads, you expected 10,000+ RPS
- Reality: Worse than platform threads due to overhead

### Solutions

**Option 1: Use Platform Threads for Native Calls**
```java
private final ExecutorService nativeExecutor = 
    Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

public CompletableFuture<Result> processImage(byte[] data) {
    return CompletableFuture.supplyAsync(
        () -> processImageNative(data),
        nativeExecutor  // Use platform thread
    );
}
```

**Option 2: Pure Java Alternatives**
```java
// Instead of native image processing:
import org.imgscalr.Scalr;

public BufferedImage resizeImage(BufferedImage img) {
    return Scalr.resize(img, 300); // Pure Java, works with virtual threads
}
```

**Option 3: Async Native Libraries**
If the native library supports async operations, use those instead.

### Detection

```bash
# Find native methods
grep -rn "native.*(" src/

# Check third-party libraries
mvn dependency:tree | grep -i "native\|jni"
```

---

## Thread Pool Misconceptions

### Misconception 1: "Keep All My Thread Pools"

**Wrong:**
```java
@Configuration
public class ThreadPoolConfig {
    @Bean
    public ExecutorService apiCallExecutor() {
        return Executors.newFixedThreadPool(50); // Unnecessary!
    }
    
    @Bean
    public ExecutorService dbQueryExecutor() {
        return Executors.newFixedThreadPool(20); // Unnecessary!
    }
}
```

**Right:**
```java
// Use virtual threads directly
public void callApi() {
    Thread.startVirtualThread(() -> makeApiCall());
}

// Or use a virtual thread executor
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
```

### Misconception 2: "Virtual Threads Are Free"

Creating a virtual thread still has overhead (about 1KB and some CPU cycles).

**Wrong:**
```java
// Creating virtual thread for every tiny operation
for (int i = 0; i < items.size(); i++) {
    Thread.startVirtualThread(() -> process(items.get(i)));
}
// Overhead dominates for small tasks
```

**Right:**
```java
// Use for I/O-bound operations
List<CompletableFuture<Result>> futures = items.stream()
    .map(item -> CompletableFuture.supplyAsync(
        () -> fetchFromApi(item),  // I/O operation
        virtualThreadExecutor))
    .toList();
```

### Misconception 3: "Change Thread Pool, Get Free Performance"

Virtual threads help with I/O-bound workloads. For CPU-bound work, they add overhead.

**CPU-Bound (No Benefit):**
```java
// Crunching numbers - no I/O
public double calculate() {
    double result = 0;
    for (int i = 0; i < 1_000_000; i++) {
        result += Math.sqrt(i) * Math.sin(i);
    }
    return result;
}
```

**I/O-Bound (Big Benefit):**
```java
// Waiting on network/database
public User fetchUser(long id) {
    return restTemplate.getForObject(
        "https://api.example.com/users/" + id, 
        User.class
    );
}
```

---

## Monitoring and Observability

### The Problem

Traditional monitoring assumes hundreds of threads, not millions.

### Metrics That Break

```java
// These metrics become useless with virtual threads
Gauge.builder("jvm.threads.live", threadCount) // 2,000,000+ always
Gauge.builder("jvm.threads.daemon", daemonCount) // Meaningless
Gauge.builder("jvm.threads.peak", peakCount) // Always growing
```

### Metrics That Matter

```java
// Virtual thread-specific metrics
Gauge.builder("jvm.threads.virtual.count", virtualThreadCount)
Gauge.builder("jvm.threads.virtual.pinned", pinnedCount) // Critical!
Gauge.builder("jvm.threads.carrier.count", carrierCount) // Usually = core count
Gauge.builder("jvm.threads.carrier.utilization", carrierUtilization) // Target 70-80%

// Application metrics remain important
Timer.builder("http.request.duration")
Counter.builder("http.requests.total")
Counter.builder("database.queries.total")
```

### Alert Rules to Update

**Old Rule (Doesn't Work):**
```yaml
# Alert when thread count > 500
- alert: HighThreadCount
  expr: jvm_threads_live > 500
```

**New Rule:**
```yaml
# Alert when carrier threads are pinned
- alert: CarrierThreadsPinned
  expr: jvm_threads_virtual_pinned > 10
  
# Alert when carrier utilization is low
- alert: LowCarrierUtilization
  expr: jvm_threads_carrier_utilization < 0.3
```

---

## Framework Assumptions

### Spring Framework

**Problem:** Some Spring features assume platform threads.

**Example: @Async with Default Executor**
```java
// This still uses fixed thread pool by default!
@Async
public void processAsync() {
    // Runs on platform thread pool
}
```

**Fix:**
```java
@Configuration
@EnableAsync
public class AsyncConfig {
    @Bean(name = "asyncExecutor")
    public Executor asyncExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

@Async("asyncExecutor")
public void processAsync() {
    // Now runs on virtual thread
}
```

### Quarkus

Works great with virtual threads out of the box:
```properties
quarkus.virtual-threads.enabled=true
```

### Micronaut

```yaml
micronaut:
  executors:
    default:
      type: virtual
```

---

## Performance Testing Pitfalls

### Pitfall 1: Unrealistic Load Tests

**Wrong:**
```bash
# Constant load doesn't test virtual threads properly
ab -n 100000 -c 200 http://localhost:8080/api
```

**Right:**
```bash
# Bursty traffic with high concurrency
wrk -t 4 -c 1000 -d 60s --script burst.lua http://localhost:8080/api
```

### Pitfall 2: Ignoring Warmup

Virtual threads need JIT compilation like anything else.

**Best Practice:**
```java
// Warmup phase
for (int i = 0; i < 10_000; i++) {
    processRequest();
}

// Now benchmark
long start = System.currentTimeMillis();
for (int i = 0; i < 100_000; i++) {
    processRequest();
}
long duration = System.currentTimeMillis() - start;
```

### Pitfall 3: Not Testing Edge Cases

Test these scenarios:
- ✅ Connection pool exhaustion
- ✅ Database timeout
- ✅ Slow endpoints (5+ second response)
- ✅ Memory pressure
- ✅ GC pauses under load
- ✅ Cascading failures

---

## Quick Reference: Common Fixes

| Problem | Detection | Fix |
|---------|-----------|-----|
| ThreadLocal bloat | Memory grows over time | Use ScopedValue or request beans |
| Carrier pinning | `jdk.VirtualThreadPinned` events | Replace synchronized with Lock |
| Pool exhaustion | Connection timeout errors | Increase pool 2.5-3x |
| Native method pinning | Native methods in hot path | Use platform thread pool for JNI |
| Useless thread dumps | Can't analyze dumps | Use JFR and distributed tracing |
| Fixed thread pools | No performance gain | Use virtual thread executors |
| Low carrier utilization | CPU idle but requests slow | Find and fix pinning |

---

## Summary: Golden Rules

1. **Audit ThreadLocal before migration**
2. **Replace synchronized in hot paths**
3. **Increase connection pools 2.5-3x**
4. **Monitor carrier thread pinning**
5. **Use JFR, not thread dumps**
6. **Remove unnecessary thread pools**
7. **Keep platform threads for native code**
8. **Test with realistic traffic patterns**
9. **Update monitoring and alerts**
10. **Virtual threads help I/O, not CPU**

---

## Next Steps

- Review the [Migration Guide](migration-guide.md) for step-by-step instructions
- Check [Metrics](metrics.md) for observability setup
- See code examples in the repository

Found a gotcha not listed here? Please contribute!
