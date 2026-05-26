package com.example;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * False-positive fixture: NONE of these patterns should be flagged.
 *
 * Key cases:
 *  - No explicit virtual-thread API call in file → Phase 1 gate blocks ALL detection
 *  - Collections.synchronizedList / synchronizedMap → method names, not keyword
 *  - ReentrantLock → correct alternative, not flagged
 *  - AtomicInteger → correct alternative, not flagged
 *  - ThreadLocal in a non-VT file → Phase 1 gate prevents detection
 *  - @Test / @PostConstruct / main() → excluded even if VT context were present
 *  - synchronized in comments or string literals → stripped by codeOnly()
 */
@Service
public class VirtualThreadsMisuseFalsePositive {

    // SAFE: ReentrantLock — correct VT-compatible synchronization
    private final ReentrantLock lock = new ReentrantLock();

    // SAFE: AtomicInteger — lock-free, never pins carrier
    private final AtomicInteger counter = new AtomicInteger();

    // SAFE: ThreadLocal in non-VT file (no VT context → Phase 1 gate prevents detection)
    private final ThreadLocal<String> context = new ThreadLocal<>();

    // SAFE: Collections.synchronizedList — method name, not the synchronized keyword
    // \bsynchronized\b requires a word boundary; 'synchronizedList' is camelCase with no boundary
    public List<String> safeSynchronizedList() {
        return Collections.synchronizedList(new ArrayList<>());
    }

    // SAFE: Collections.synchronizedMap — same reasoning
    public java.util.Map<String, Object> safeSynchronizedMap() {
        return Collections.synchronizedMap(new java.util.HashMap<>());
    }

    // SAFE: ReentrantLock.lock() + unlock() — the right pattern
    public void safeWithReentrantLock(String data) {
        lock.lock();
        try {
            System.out.println(data);
        } finally {
            lock.unlock();
        }
    }

    // SAFE: AtomicInteger operations — never pin
    public int safeAtomicIncrement() {
        return counter.incrementAndGet();
    }

    // SAFE: @PostConstruct — startup context, excluded even if VT were present
    @PostConstruct
    public synchronized void init() {
        System.out.println("init");
    }

    // SAFE: main() — excluded even if VT were present
    public static void main(String[] args) {
        System.out.println("started");
    }

    // SAFE: @Test with synchronized — excluded
    @org.junit.jupiter.api.Test
    public void testWithSync() throws InterruptedException {
        synchronized (this) {
            Thread.sleep(10); // inside @Test — never flagged
        }
    }

    // SAFE: 'synchronized' inside a // comment
    public void documentedMethod() {
        // using synchronized here would pin the carrier thread — use ReentrantLock instead
        lock.lock();
        try {
            System.out.println("ok");
        } finally {
            lock.unlock();
        }
    }

    // SAFE: "synchronized" inside a string literal
    public void logWarning() {
        String hint = "avoid synchronized blocks with virtual threads — use ReentrantLock";
        System.out.println(hint);
    }

    // SAFE: Thread.sleep() outside of @Async and without VT context
    public void safeSleep() throws InterruptedException {
        Thread.sleep(100); // not in VT context → Phase 1 blocks detection
    }
}
