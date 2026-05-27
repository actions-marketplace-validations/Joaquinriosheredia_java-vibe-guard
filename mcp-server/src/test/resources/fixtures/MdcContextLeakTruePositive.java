package com.example;

import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * True-positive fixture: every method below leaks MDC context and MUST be flagged.
 */
@Component
public class MdcContextLeakTruePositive {

    // TRUE POSITIVE: @Async + MDC.put() without clear → CRITICAL
    // Thread pool thread reuses this MDC entry in the next submitted task
    @Async
    public void handleEvent(String eventId) {
        MDC.put("eventId", eventId); // BUG: never cleared
        processEvent(eventId);
    }

    // TRUE POSITIVE: @Async + multiple MDC.put() without clear → CRITICAL (on first put)
    @Async
    public void enrichContext(String traceId, String spanId) {
        MDC.put("traceId", traceId); // BUG: first put — issue reported here
        MDC.put("spanId", spanId);   // second put, not separately reported
        doWork();
    }

    // TRUE POSITIVE: @Scheduled + MDC.put() without clear → CRITICAL
    // Scheduled methods run repeatedly on the same pooled thread
    @Scheduled(fixedDelay = 5000)
    public void scheduledReport() {
        MDC.put("job", "scheduled-report"); // BUG: accumulates on each execution
        generateReport();
    }

    // TRUE POSITIVE: @Scheduled(cron) + MDC.put() without clear → CRITICAL
    @Scheduled(cron = "0 * * * * *")
    public void cronJob() {
        MDC.put("trigger", "cron"); // BUG: never cleared
        runJob();
    }

    // TRUE POSITIVE: @Async with conditional clear (not guaranteed) → CRITICAL
    // MDC.clear() inside an if-block is NOT a reliable cleanup — rule flags this
    @Async
    public void conditionalClear(String id, boolean shouldClear) {
        MDC.put("id", id); // BUG: clear is not guaranteed
        if (shouldClear) {
            // MDC.clear() intentionally absent here to keep as true positive
            doWork();
        }
        doWork();
    }

    // TRUE POSITIVE: fully qualified org.slf4j.MDC.put() also triggers detection
    @Async
    public void fullyQualified(String traceId) {
        org.slf4j.MDC.put("traceId", traceId); // BUG: never cleared
        doWork();
    }

    private void processEvent(String e) {}
    private void doWork() {}
    private void generateReport() {}
    private void runJob() {}
}
