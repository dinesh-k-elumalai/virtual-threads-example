# Virtual Threads Metrics and Observability

Complete guide to monitoring virtual threads in production with metrics, alerts, and dashboards.

## Table of Contents
1. [Essential Metrics](#essential-metrics)
2. [Micrometer Setup](#micrometer-setup)
3. [Prometheus Configuration](#prometheus-configuration)
4. [Grafana Dashboards](#grafana-dashboards)
5. [JFR (Java Flight Recorder)](#jfr-java-flight-recorder)
6. [Alert Rules](#alert-rules)
7. [Logging Best Practices](#logging-best-practices)
8. [Distributed Tracing](#distributed-tracing)

---

## Essential Metrics

### Virtual Thread Metrics

These are the core metrics you need to monitor virtual threads effectively:

| Metric | Type | Description | Target Value |
|--------|------|-------------|--------------|
| `jvm.threads.virtual.count` | Gauge | Current number of virtual threads | Varies by load |
| `jvm.threads.virtual.created.total` | Counter | Total virtual threads created | Growing steadily |
| `jvm.threads.virtual.pinned` | Gauge | Virtual threads pinned to carriers | < 10 |
| `jvm.threads.carrier.count` | Gauge | Number of carrier threads | = CPU cores |
| `jvm.threads.carrier.utilization` | Gauge | Carrier thread utilization % | 60-80% |
| `jvm.threads.platform.count` | Gauge | Platform threads (non-carrier) | Minimize |

### Application Metrics

Standard application metrics remain important:

| Metric | Type | Description | What to Watch |
|--------|------|-------------|---------------|
| `http.request.duration` | Histogram | Request latency | P95, P99 |
| `http.requests.total` | Counter | Total requests | Rate of change |
| `database.queries.duration` | Histogram | Query latency | Increase may indicate issues |
| `database.connections.active` | Gauge | Active connections | Should not hit max |
| `database.connections.idle` | Gauge | Idle connections | Monitor pool efficiency |
| `jvm.memory.used` | Gauge | Heap usage | Watch for ThreadLocal bloat |

---

## Micrometer Setup

### Spring Boot Configuration

Add Micrometer dependencies to `pom.xml`:

```xml
<dependencies>
    <!-- Micrometer core -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-core</artifactId>
    </dependency>
    
    <!-- Prometheus registry -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
</dependencies>
```

### Custom Virtual Thread Metrics

```java
package com.example.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

@Component
public class VirtualThreadMetrics implements MeterBinder {

    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    @Override
    public void bindTo(MeterRegistry registry) {
        
        // Virtual thread count
        Gauge.builder("jvm.threads.virtual.count", this::getVirtualThreadCount)
            .description("Current number of virtual threads")
            .register(registry);
        
        // Pinned virtual threads (CRITICAL)
        Gauge.builder("jvm.threads.virtual.pinned", this::getPinnedVirtualThreads)
            .description("Number of virtual threads pinned to carrier threads")
            .baseUnit("threads")
            .register(registry);
        
        // Carrier thread count
        Gauge.builder("jvm.threads.carrier.count", this::getCarrierThreadCount)
            .description("Number of carrier threads (platform threads running virtual threads)")
            .baseUnit("threads")
            .register(registry);
        
        // Carrier utilization
        Gauge.builder("jvm.threads.carrier.utilization", this::getCarrierUtilization)
            .description("Carrier thread utilization percentage")
            .baseUnit("percent")
            .register(registry);
        
        // Platform thread count (for comparison)
        Gauge.builder("jvm.threads.platform.count", this::getPlatformThreadCount)
            .description("Number of platform threads (non-carrier)")
            .baseUnit("threads")
            .register(registry);
    }

    private long getVirtualThreadCount() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(Thread::isVirtual)
            .count();
    }

    private long getPinnedVirtualThreads() {
        // Use JFR or custom tracking
        // This is a simplified version
        return Thread.getAllStackTraces().keySet().stream()
            .filter(Thread::isVirtual)
            .filter(this::isPinned)
            .count();
    }
    
    private boolean isPinned(Thread thread) {
        // Check if thread is in pinned state
        // This requires JFR events or custom instrumentation
        StackTraceElement[] stack = thread.getStackTrace();
        return stack.length > 0 && 
               containsSynchronized(stack);
    }
    
    private boolean containsSynchronized(StackTraceElement[] stack) {
        // Simple heuristic - check for common pinning patterns
        for (StackTraceElement element : stack) {
            String method = element.getMethodName();
            if (method.contains("synchronized") || 
                element.getClassName().contains("ObjectMonitor")) {
                return true;
            }
        }
        return false;
    }

    private int getCarrierThreadCount() {
        // Carrier threads typically equal CPU core count
        return Runtime.getRuntime().availableProcessors();
    }

    private double getCarrierUtilization() {
        // Calculate based on carrier thread activity
        // This is simplified - use JFR for accurate measurement
        long totalThreads = threadBean.getThreadCount();
        int carriers = getCarrierThreadCount();
        return Math.min(100.0, (totalThreads * 100.0) / carriers);
    }
    
    private long getPlatformThreadCount() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(t -> !t.isVirtual())
            .count();
    }
}
```

### Application Properties

```properties
# Enable Prometheus endpoint
management.endpoints.web.exposure.include=health,metrics,prometheus
management.endpoint.prometheus.enabled=true
management.metrics.export.prometheus.enabled=true

# Metric collection intervals
management.metrics.export.prometheus.step=10s

# Enable JVM metrics
management.metrics.enable.jvm=true
management.metrics.enable.process=true
management.metrics.enable.system=true
```

---

## Prometheus Configuration

### prometheus.yml

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'spring-boot-app'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8080']
        labels:
          application: 'virtual-threads-demo'
          environment: 'production'
    
    # Scrape more frequently for virtual thread metrics
    scrape_interval: 10s
```

### Key Queries

```promql
# Virtual thread count over time
jvm_threads_virtual_count

# Rate of virtual thread creation
rate(jvm_threads_virtual_created_total[5m])

# Pinned threads (ALERT on this)
jvm_threads_virtual_pinned

# Carrier utilization
jvm_threads_carrier_utilization

# Request throughput
rate(http_requests_total[1m])

# P95 latency
histogram_quantile(0.95, rate(http_request_duration_seconds_bucket[5m]))

# P99 latency
histogram_quantile(0.99, rate(http_request_duration_seconds_bucket[5m]))

# Database connection pool usage
database_connections_active / database_connections_max

# Memory usage trend
rate(jvm_memory_used_bytes{area="heap"}[5m])
```

---

## Grafana Dashboards

### Dashboard 1: Virtual Threads Overview

```json
{
  "dashboard": {
    "title": "Virtual Threads - Production Overview",
    "panels": [
      {
        "title": "Virtual Thread Count",
        "targets": [{
          "expr": "jvm_threads_virtual_count",
          "legendFormat": "Virtual Threads"
        }],
        "type": "graph"
      },
      {
        "title": "Pinned Virtual Threads (CRITICAL)",
        "targets": [{
          "expr": "jvm_threads_virtual_pinned",
          "legendFormat": "Pinned Threads"
        }],
        "type": "graph",
        "alert": {
          "conditions": [{
            "evaluator": {
              "params": [10],
              "type": "gt"
            }
          }]
        }
      },
      {
        "title": "Carrier Thread Utilization",
        "targets": [{
          "expr": "jvm_threads_carrier_utilization",
          "legendFormat": "Utilization %"
        }],
        "type": "gauge",
        "thresholds": [
          { "value": 0, "color": "red" },
          { "value": 30, "color": "yellow" },
          { "value": 60, "color": "green" },
          { "value": 90, "color": "red" }
        ]
      },
      {
        "title": "Thread Creation Rate",
        "targets": [{
          "expr": "rate(jvm_threads_virtual_created_total[5m])",
          "legendFormat": "Threads/sec"
        }],
        "type": "graph"
      }
    ]
  }
}
```

### Dashboard 2: Performance Comparison

**Panel Configuration:**

1. **Request Latency Comparison**
   - Query: `histogram_quantile(0.95, rate(http_request_duration_seconds_bucket[5m]))`
   - Compare before/after virtual threads deployment

2. **Throughput**
   - Query: `rate(http_requests_total[1m])`
   - Show requests per second

3. **Memory Usage**
   - Query: `jvm_memory_used_bytes{area="heap"}`
   - Track heap usage over time

4. **Database Connections**
   - Query: `database_connections_active`
   - Monitor connection pool utilization

### Dashboard 3: Troubleshooting

**Panels for debugging issues:**

1. **Pinning Hotspots**
   ```promql
   topk(10, jvm_threads_virtual_pinned)
   ```

2. **Error Rate**
   ```promql
   rate(http_requests_total{status=~"5.."}[5m])
   ```

3. **GC Activity**
   ```promql
   rate(jvm_gc_pause_seconds_count[5m])
   ```

4. **Connection Pool Exhaustion**
   ```promql
   (database_connections_active / database_connections_max) * 100
   ```

---

## JFR (Java Flight Recorder)

JFR is the most powerful tool for debugging virtual threads.

### Starting a Recording

```bash
# Start JFR recording
jcmd <pid> JFR.start name=vthreads settings=profile duration=60s filename=/tmp/recording.jfr

# Or with specific events
jcmd <pid> JFR.start name=vthreads \
  settings=profile \
  event=jdk.VirtualThreadPinned,jdk.VirtualThreadSubmitFailed \
  duration=60s \
  filename=/tmp/recording.jfr
```

### Key JFR Events

| Event | What It Shows | When to Use |
|-------|---------------|-------------|
| `jdk.VirtualThreadStart` | Virtual thread creation | Track creation patterns |
| `jdk.VirtualThreadEnd` | Virtual thread termination | Find long-lived threads |
| `jdk.VirtualThreadPinned` | Pinning occurrences | **Critical for debugging** |
| `jdk.VirtualThreadSubmitFailed` | Failed submissions | Capacity issues |
| `jdk.ThreadPark` | Thread parking events | Find blocking operations |
| `jdk.ThreadSleep` | Sleep events | Identify I/O patterns |

### Analyzing Recordings

```bash
# Print pinned thread events
jfr print --events jdk.VirtualThreadPinned recording.jfr

# Print all virtual thread events
jfr print --events jdk.VirtualThread* recording.jfr

# Export to JSON for analysis
jfr print --json recording.jfr > analysis.json

# View in JDK Mission Control (GUI)
jmc recording.jfr
```

### Custom JFR Event

```java
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name("com.example.VirtualThreadOperation")
@Label("Virtual Thread Operation")
public class VirtualThreadOperationEvent extends Event {
    @Label("Operation Type")
    public String operationType;
    
    @Label("Duration")
    public long durationMs;
}

// Usage
public void performOperation() {
    VirtualThreadOperationEvent event = new VirtualThreadOperationEvent();
    event.begin();
    event.operationType = "database_query";
    
    try {
        // Your operation
        executeQuery();
    } finally {
        event.durationMs = System.currentTimeMillis() - event.startTime;
        event.commit();
    }
}
```

---

## Alert Rules

### Prometheus Alert Rules

```yaml
# alerts.yml
groups:
  - name: virtual_threads
    interval: 30s
    rules:
      
      # CRITICAL: Carrier threads are pinned
      - alert: HighVirtualThreadPinning
        expr: jvm_threads_virtual_pinned > 10
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "High virtual thread pinning detected"
          description: "{{ $value }} virtual threads are pinned, blocking carriers"
      
      # WARNING: Low carrier utilization
      - alert: LowCarrierUtilization
        expr: jvm_threads_carrier_utilization < 30
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Low carrier thread utilization"
          description: "Carrier utilization is {{ $value }}%, may indicate pinning"
      
      # CRITICAL: Connection pool exhaustion
      - alert: DatabaseConnectionPoolExhausted
        expr: (database_connections_active / database_connections_max) > 0.9
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Database connection pool near exhaustion"
          description: "{{ $value | humanizePercentage }} of connections in use"
      
      # WARNING: Memory growth (ThreadLocal leak?)
      - alert: MemoryGrowth
        expr: rate(jvm_memory_used_bytes{area="heap"}[10m]) > 10000000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Rapid memory growth detected"
          description: "Heap growing at {{ $value | humanize }}B/s, check for ThreadLocal leaks"
      
      # CRITICAL: High P99 latency
      - alert: HighLatency
        expr: histogram_quantile(0.99, rate(http_request_duration_seconds_bucket[5m])) > 1
        for: 3m
        labels:
          severity: critical
        annotations:
          summary: "High P99 latency"
          description: "P99 latency is {{ $value }}s"
```

### Alert Response Playbook

**Alert: HighVirtualThreadPinning**
1. Check JFR recording: `jfr print --events jdk.VirtualThreadPinned`
2. Identify synchronized blocks in stack traces
3. Review recent deployments for new synchronized code
4. Consider rollback if critical

**Alert: LowCarrierUtilization**
1. Check for pinning: Review `jvm_threads_virtual_pinned` metric
2. Look for native method calls in hot path
3. Check if workload is actually CPU-bound (not I/O-bound)

**Alert: DatabaseConnectionPoolExhausted**
1. Check active query count: `SELECT count(*) FROM pg_stat_activity WHERE state='active'`
2. Review slow query log
3. Consider increasing pool size temporarily
4. Investigate if new code has connection leaks

---

## Logging Best Practices

### Structured Logging with Correlation IDs

```java
@Component
public class CorrelationIdFilter implements Filter {
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, 
                        FilterChain chain) throws IOException, ServletException {
        
        String correlationId = getOrCreateCorrelationId(request);
        MDC.put("correlationId", correlationId);
        MDC.put("threadType", Thread.currentThread().isVirtual() ? "virtual" : "platform");
        
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
    
    private String getOrCreateCorrelationId(ServletRequest request) {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String correlationId = httpRequest.getHeader("X-Correlation-ID");
        return correlationId != null ? correlationId : UUID.randomUUID().toString();
    }
}
```

### Log Format

```properties
# application.properties
logging.pattern.console=%d{yyyy-MM-dd HH:mm:ss} [%X{correlationId}] [%X{threadType}] %-5level %logger{36} - %msg%n
```

### Example Logs

```
2024-11-24 10:15:23 [abc-123-def] [virtual] INFO  c.e.UserService - Fetching user 456
2024-11-24 10:15:23 [abc-123-def] [virtual] DEBUG c.e.DatabaseService - Executing query: SELECT * FROM users WHERE id = 456
2024-11-24 10:15:24 [abc-123-def] [virtual] INFO  c.e.UserService - User 456 fetched in 50ms
```

---

## Distributed Tracing

### OpenTelemetry Setup

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-spring-boot-starter</artifactId>
</dependency>
```

### Configuration

```java
@Configuration
public class TracingConfig {
    
    @Bean
    public OpenTelemetry openTelemetry() {
        return OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(spanProcessor())
                    .build()
            )
            .setPropagators(
                ContextPropagators.create(
                    W3CTraceContextPropagator.getInstance()
                )
            )
            .build();
    }
    
    private SpanProcessor spanProcessor() {
        return BatchSpanProcessor.builder(
            OtlpGrpcSpanExporter.builder()
                .setEndpoint("http://jaeger:4317")
                .build()
        ).build();
    }
}
```

### Custom Spans for Virtual Threads

```java
@Service
public class UserService {
    
    private final Tracer tracer;
    
    public UserService(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("com.example.user-service");
    }
    
    public User fetchUser(Long userId) {
        Span span = tracer.spanBuilder("fetchUser")
            .setAttribute("user.id", userId)
            .setAttribute("thread.type", Thread.currentThread().isVirtual() ? "virtual" : "platform")
            .startSpan();
        
        try (Scope scope = span.makeCurrent()) {
            // Your code
            return userRepository.findById(userId);
        } catch (Exception e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
```

---

## Metrics Checklist

Before going to production, ensure you have:

- [ ] Virtual thread count gauge
- [ ] Pinned thread count gauge (CRITICAL)
- [ ] Carrier utilization gauge
- [ ] Virtual thread creation counter
- [ ] Request latency histogram (P50, P95, P99)
- [ ] Throughput counter
- [ ] Database connection metrics
- [ ] Memory usage metrics
- [ ] GC metrics
- [ ] Error rate counter
- [ ] JFR recording capability
- [ ] Correlation ID in logs
- [ ] Distributed tracing setup
- [ ] Alert rules configured
- [ ] Grafana dashboards deployed

---

## Next Steps

1. **Implement metrics** using the code examples above
2. **Create dashboards** in Grafana
3. **Set up alerts** in Prometheus
4. **Test alerting** by simulating issues
5. **Document runbooks** for each alert

For more information:
- [Migration Guide](migration-guide.md) - Step-by-step migration
- [Gotchas](gotchas.md) - Common pitfalls and solutions
- [Repository README](../README.md) - Overview and examples
