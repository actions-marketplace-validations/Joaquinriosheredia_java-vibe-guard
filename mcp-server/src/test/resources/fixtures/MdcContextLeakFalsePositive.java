package com.example;

import jakarta.annotation.PostConstruct;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * False-positive fixture: NONE of these patterns should be flagged.
 */
@Service
public class MdcContextLeakFalsePositive {

    // SAFE: @Async + MDC.put() with try/finally { MDC.clear() } — correct pattern
    @Async
    public void handleEventSafe(String eventId) {
        MDC.put("eventId", eventId);
        try {
            processEvent(eventId);
        } finally {
            MDC.clear(); // proper cleanup — not flagged
        }
    }

    // SAFE: @Async + MDC.put() with MDC.clear() at end (no try/finally but still clears)
    @Async
    public void handleEventWithClear(String traceId) {
        MDC.put("traceId", traceId);
        doWork();
        MDC.clear(); // explicit clear — not flagged
    }

    // SAFE: @Scheduled + MDC.put() with try/finally { MDC.clear() }
    @Scheduled(fixedDelay = 1000)
    public void scheduledJobSafe() {
        MDC.put("job", "batch");
        try {
            runBatch();
        } finally {
            MDC.clear(); // proper cleanup — not flagged
        }
    }

    // SAFE: plain @Service method with MDC.put() and no clear
    // Generic service methods are explicitly excluded — too noisy without thread-pool context
    public void serviceMethodWithMdc(String id) {
        MDC.put("id", id);
        doWork(); // NOT @Async / @Scheduled → never flagged
    }

    // SAFE: MDC.get() without put → no leak risk, never flagged
    @Async
    public void readMdcOnly() {
        String traceId = MDC.get("traceId");
        doWork();
    }

    // SAFE: comment containing MDC.put() — stripped by codeOnly()
    @Async
    public void documentedMethod(String eventId) {
        // MDC.put("eventId", eventId) — always call MDC.clear() in finally
        doWork();
    }

    // SAFE: string literal containing MDC.put() — stripped by codeOnly()
    @Async
    public void logWarning() {
        String hint = "pattern: MDC.put(key, value); try { ... } finally { MDC.clear(); }";
        doWork();
    }

    // SAFE: @Test excluded even with @Async and MDC.put() without clear
    @org.junit.jupiter.api.Test
    @Async
    public void testAsync() {
        MDC.put("test", "value");
        doWork();
    }

    // SAFE: @PostConstruct excluded
    @PostConstruct
    public void init() {
        MDC.put("startup", "true");
        doWork();
    }

    // SAFE: main() excluded
    public static void main(String[] args) {
        MDC.put("main", "true");
    }

    private void processEvent(String e) {}
    private void doWork() {}
    private void runBatch() {}
}
