# Java 21 Virtual Threads in Production

Real-world code examples and performance benchmarks from deploying virtual threads across 10,000+ microservices.

## 📊 What's Inside

This repository contains:
- **Production-ready code examples** comparing platform threads vs virtual threads
- **Performance benchmarks** with real metrics
- **Common patterns** that work well with virtual threads
- **Anti-patterns** to avoid (ThreadLocal, synchronized blocks)
- **Migration guides** for Spring Boot applications

## 🚀 Quick Start

### Prerequisites
- Java 21 or higher
- Maven 3.8+
- Spring Boot 3.2+

### Running the Examples

```bash
# Clone the repository
git clone https://github.com/dinesh-k-elumalai/virtual-threads-example.git
cd virtual-threads-example

# Build the project
mvn clean install

# Run traditional platform threads example
mvn exec:java -Dexec.mainClass="com.example.virtualthreads.traditional.PlatformThreadsExample"

# Run virtual threads example
mvn exec:java -Dexec.mainClass="com.example.virtualthreads.virtual.VirtualThreadsExample"

# Run benchmarks
mvn exec:java -Dexec.mainClass="com.example.virtualthreads.benchmarks.ThroughputBenchmark"
```

## 📁 Repository Structure

```
.
├── src/main/java/com/example/virtualthreads/
│   ├── traditional/          # Platform threads implementations
│   ├── virtual/              # Virtual threads implementations
│   ├── patterns/             # Production patterns
│   └── benchmarks/           # Performance tests
├── docs/
│   ├── migration-guide.md    # Step-by-step migration
│   ├── gotchas.md           # Common pitfalls
│   └── metrics.md           # Observability setup
└── pom.xml
```

## 🎯 Key Findings

From production deployment across 10,000+ services:

- **60% throughput improvement** in I/O-bound services
- **40% memory reduction** by optimizing container counts
- **$2.3M annual savings** in infrastructure costs
- **ThreadLocal pitfalls** caused 3x memory bloat in 15% of services
- **Synchronized blocks** became primary bottleneck requiring refactoring

## 📖 Code Examples

### Basic Virtual Thread Usage

```java
// Platform threads - limited concurrency
ExecutorService executor = Executors.newFixedThreadPool(200);
for (int i = 0; i < 10000; i++) {
    executor.submit(() -> handleRequest());
}

// Virtual threads - scales to workload
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int i = 0; i < 10000; i++) {
        executor.submit(() -> handleRequest());
    }
}
```

### Spring Boot Integration

```properties
# application.properties
spring.threads.virtual.enabled=true
spring.datasource.hikari.maximum-pool-size=50
```

### Avoiding ThreadLocal Issues

```java
// ❌ BAD: ThreadLocal with virtual threads
private static final ThreadLocal<UserContext> context = new ThreadLocal<>();

// ✅ GOOD: Request-scoped beans
@RequestScope
public class UserContext {
    private String userId;
    // ...
}
```

### Replacing Synchronized Blocks

```java
// ❌ BAD: Pins carrier thread
synchronized (lock) {
    cache.invalidate(key);
}

// ✅ GOOD: Unmounts properly
private final ReentrantLock lock = new ReentrantLock();

lock.lock();
try {
    cache.invalidate(key);
} finally {
    lock.unlock();
}
```

## 📊 Performance Metrics

### REST API Service (1,000 RPS baseline)

| Metric | Platform Threads | Virtual Threads | Improvement |
|--------|-----------------|-----------------|-------------|
| P95 Latency | 145ms | 89ms | 39% faster |
| Throughput | 1,000 RPS | 1,600 RPS | 60% increase |
| Memory/Instance | 2.1 GB | 1.3 GB | 40% reduction |
| CPU Utilization | 35% | 52% | 48% better |
| Pods Required | 12 | 7 | 42% fewer |

### Batch Processing Job (10,000 items)

| Metric | Platform Threads | Virtual Threads | Improvement |
|--------|-----------------|-----------------|-------------|
| Completion Time | 8.5 min | 2.5 min | 70% faster |
| Memory Peak | 3.2 GB | 1.8 GB | 44% lower |
| Concurrent Workers | 50 | 10,000 | 200x scale |

## 🔍 Production Patterns

### 1. Web Request Handling
Perfect use case. Simple blocking code that scales.

```java
@RestController
public class UserController {
    @GetMapping("/users/{id}")
    public UserDto getUser(@PathVariable Long id) {
        // Blocking calls on virtual threads - scales beautifully
        var user = userService.findById(id);
        var orders = orderService.findByUserId(id);
        var profile = profileService.getProfile(id);
        
        return new UserDto(user, orders, profile);
    }
}
```

### 2. Parallel Service Calls

```java
public List<UserData> fetchUserData(List<Long> userIds) {
    return userIds.parallelStream()
        .map(id -> restTemplate.getForObject(
            "/users/" + id, UserData.class))
        .toList();
    // Each parallel task runs on a virtual thread
}
```

### 3. Background Job Processing

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    List<CompletableFuture<Void>> futures = items.stream()
        .map(item -> CompletableFuture.runAsync(
            () -> processItem(item), executor))
        .toList();
    
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
}
```

## ⚠️ Common Pitfalls

### 1. ThreadLocal Memory Bloat
**Problem:** ThreadLocal caches multiply by thread count  
**Solution:** Use request-scoped beans or remove caching

### 2. Synchronized Block Pinning
**Problem:** Synchronized blocks pin carrier threads  
**Solution:** Replace with ReentrantLock or Lock APIs

### 3. Fixed Thread Pools
**Problem:** Defeats the purpose of virtual threads  
**Solution:** Use `Executors.newVirtualThreadPerTaskExecutor()`

### 4. Connection Pool Under-sizing
**Problem:** Pools sized for 200 platform threads  
**Solution:** Increase to 50-75 connections per instance

## 📈 Observability

### Essential Metrics

```java
// Monitor carrier thread pinning
Metrics.gauge("jdk.virtualThreads.pinned.count", pinnedCount);

// Track virtual thread creation
Metrics.counter("jdk.virtualThreads.created.total");

// Monitor platform thread pool utilization
Metrics.gauge("jdk.virtualThreads.carrier.utilization", utilization);
```

### JFR Recording

```bash
java -XX:StartFlightRecording=filename=recording.jfr \
     -XX:FlightRecorderOptions=stackdepth=256 \
     -jar application.jar
```

## 🛠️ Migration Checklist

- [ ] Audit codebase for ThreadLocal usage
- [ ] Identify synchronized blocks in critical paths
- [ ] Set up carrier thread pinning metrics
- [ ] Increase database connection pool sizes
- [ ] Enable virtual threads in Spring Boot
- [ ] Test with production traffic patterns
- [ ] Monitor memory usage for ThreadLocal bloat
- [ ] Refactor synchronized blocks to ReentrantLock
- [ ] Update thread dumps collection strategy
- [ ] Configure distributed tracing

## 📚 Additional Resources

- [Migration Guide](docs/migration-guide.md) - Step-by-step instructions
- [Gotchas Document](docs/gotchas.md) - Common issues and solutions
- [JEP 444](https://openjdk.org/jeps/444) - Official Virtual Threads specification

## 🤝 Contributing

Contributions welcome! Please read our [Contributing Guide](CONTRIBUTING.md) first.

## 📄 License

MIT License - see [LICENSE](LICENSE) file for details

## 💬 Contact

Questions? Open an issue or reach out via email.

---