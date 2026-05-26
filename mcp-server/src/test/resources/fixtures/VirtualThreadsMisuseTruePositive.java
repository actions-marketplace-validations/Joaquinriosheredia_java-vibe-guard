package com.example;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

/**
 * True-positive fixture: all patterns below MUST be flagged.
 * The file uses explicit VT API → Phase 1 gate passes.
 */
@Service
public class VirtualThreadsMisuseTruePositive {

    private final ReentrantLock lock = new ReentrantLock();

    // Explicit VT context — activates detection for the whole file
    private final java.util.concurrent.ExecutorService vtPool =
        Executors.newVirtualThreadPerTaskExecutor();

    // TRUE POSITIVE: ThreadLocal creation in VT context → MAJOR
    // With millions of VTs each holding a slot, memory leaks until VT exits
    private final ThreadLocal<String> requestId = new ThreadLocal<>();

    // TRUE POSITIVE: InheritableThreadLocal creation → MAJOR
    private final InheritableThreadLocal<String> traceId = new InheritableThreadLocal<>();

    // TRUE POSITIVE: ThreadLocal.withInitial → MAJOR
    private final ThreadLocal<Integer> counter = ThreadLocal.withInitial(() -> 0);

    // TRUE POSITIVE: synchronized method → CRITICAL
    // Pins the carrier platform thread for the entire method duration
    public synchronized void synchronizedMethod(String data) {
        System.out.println(data);
    }

    // TRUE POSITIVE: synchronized block → CRITICAL
    public void methodWithSyncBlock(Object monitor, String data) {
        synchronized (monitor) {
            System.out.println(data);
        }
    }

    // TRUE POSITIVE: Thread.sleep() inside synchronized block → CRITICAL (pinning)
    // VT cannot unmount from carrier while holding a monitor
    public void syncBlockWithSleep(Object monitor) throws InterruptedException {
        synchronized (monitor) {
            Thread.sleep(100); // BUG: pins carrier thread
        }
    }

    // TRUE POSITIVE: JDBC inside synchronized method → CRITICAL (pinning)
    public synchronized void syncMethodWithJdbc(java.sql.Connection connection) throws Exception {
        var stmt = connection.prepareStatement("SELECT 1"); // BUG: carrier pinned
    }

    // TRUE POSITIVE: Thread.sleep() in @Async VT method → MAJOR
    @Async
    public void asyncMethodWithSleep() throws InterruptedException {
        Thread.sleep(500); // BUG: blocks carrier in @Async VT executor
    }

    // TRUE POSITIVE: synchronized block with RestTemplate → CRITICAL (pinning)
    public void syncBlockWithRestTemplate(Object monitor,
                                          org.springframework.web.client.RestTemplate restTemplate) {
        synchronized (monitor) {
            restTemplate.getForObject("http://example.com", String.class); // BUG: pinning
        }
    }

    // TRUE POSITIVE: synchronized block with Future.get() → CRITICAL (pinning)
    public void syncBlockWithFutureGet(Object monitor, Future<String> future) throws Exception {
        synchronized (monitor) {
            String result = future.get(); // BUG: blocks carrier under monitor
        }
    }
}
