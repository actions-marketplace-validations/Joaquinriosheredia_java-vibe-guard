package com.vibeguard.mcp.rules;

import com.vibeguard.mcp.dto.FileContent;
import com.vibeguard.mcp.dto.Issue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VirtualThreadsMisuseRuleTest {

    private final VirtualThreadsMisuseRule rule = new VirtualThreadsMisuseRule();

    // -------------------------------------------------------------------------
    // Fixture-based: real files, real patterns
    // -------------------------------------------------------------------------

    @Test
    void detectsAllVtMisusePatterns() throws IOException {
        String code = loadFixture("VirtualThreadsMisuseTruePositive.java");
        List<Issue> issues = rule.analyze(FileContent.of("VirtualThreadsMisuseTruePositive.java", code));

        assertThat(issues)
            .as("Expected multiple CRITICAL/MAJOR issues in the true-positive fixture")
            .isNotEmpty();

        assertThat(issues).allSatisfy(issue -> {
            assertThat(issue.ruleId()).isEqualTo("VIBE-004");
            assertThat(issue.severity()).isIn("CRITICAL", "MAJOR");
        });
    }

    @Test
    void zeroFalsePositivesInSafeContexts() throws IOException {
        String code = loadFixture("VirtualThreadsMisuseFalsePositive.java");
        List<Issue> issues = rule.analyze(FileContent.of("VirtualThreadsMisuseFalsePositive.java", code));

        assertThat(issues)
            .as("No issues expected: file has no VT context → Phase 1 gate blocks all detection")
            .isEmpty();
    }

    // -------------------------------------------------------------------------
    // True positives — inline, granular
    // -------------------------------------------------------------------------

    @Test
    void synchronizedMethodInVtContextIsCritical() {
        String code = """
            package com.example;
            import java.util.concurrent.Executors;

            public class Svc {
                private final java.util.concurrent.ExecutorService pool =
                    Executors.newVirtualThreadPerTaskExecutor();

                public synchronized void handle(String data) {
                    System.out.println(data);
                }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).isNotEmpty();
        assertThat(issues).anySatisfy(i -> {
            assertThat(i.severity()).isEqualTo("CRITICAL");
            assertThat(i.message()).contains("synchronized");
        });
    }

    @Test
    void synchronizedBlockInVtContextIsCritical() {
        String code = """
            package com.example;
            import java.util.concurrent.Executors;

            public class Svc {
                private final java.util.concurrent.ExecutorService pool =
                    Executors.newVirtualThreadPerTaskExecutor();

                public void handle(Object monitor, String data) {
                    synchronized (monitor) {
                        System.out.println(data);
                    }
                }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).isNotEmpty();
        assertThat(issues).anySatisfy(i -> assertThat(i.severity()).isEqualTo("CRITICAL"));
    }

    @Test
    void threadLocalCreationInVtContextIsMajor() {
        String code = """
            package com.example;

            public class Svc {
                private final java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

                private final ThreadLocal<String> local = new ThreadLocal<>();
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).isNotEmpty();
        assertThat(issues).anySatisfy(i -> {
            assertThat(i.severity()).isEqualTo("MAJOR");
            assertThat(i.message()).contains("ThreadLocal");
        });
    }

    @Test
    void inheritableThreadLocalInVtContextIsMajor() {
        String code = """
            package com.example;

            public class Svc {
                private final java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

                private final InheritableThreadLocal<String> trace = new InheritableThreadLocal<>();
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).isNotEmpty();
        assertThat(issues).anySatisfy(i -> assertThat(i.severity()).isEqualTo("MAJOR"));
    }

    @Test
    void threadLocalWithInitialInVtContextIsMajor() {
        String code = """
            package com.example;

            public class Svc {
                private final java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

                private final ThreadLocal<Integer> count = ThreadLocal.withInitial(() -> 0);
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).isNotEmpty();
        assertThat(issues).anySatisfy(i -> assertThat(i.severity()).isEqualTo("MAJOR"));
    }

    @Test
    void threadSleepInAsyncVtMethodIsMajor() {
        String code = """
            package com.example;
            import org.springframework.scheduling.annotation.Async;

            public class Svc {
                private final java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

                @Async
                public void delayedTask() throws InterruptedException {
                    Thread.sleep(500);
                }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).isNotEmpty();
        assertThat(issues).anySatisfy(i -> {
            assertThat(i.severity()).isEqualTo("MAJOR");
            assertThat(i.message()).contains("sleep");
        });
    }

    @Test
    void blockingIoInsideSyncBlockIsCriticalPinning() {
        String code = """
            package com.example;

            public class Svc {
                private final java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

                public void fetch(Object monitor) throws InterruptedException {
                    synchronized (monitor) {
                        Thread.sleep(100);
                    }
                }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).isNotEmpty();
        assertThat(issues).anySatisfy(i -> {
            assertThat(i.severity()).isEqualTo("CRITICAL");
            assertThat(i.message()).contains("Pinning");
        });
    }

    @Test
    void threadOfVirtualActivatesVtContext() {
        String code = """
            package com.example;

            public class Svc {
                public void run() {
                    Thread.ofVirtual().start(() -> System.out.println("vt"));
                }

                private final ThreadLocal<String> local = new ThreadLocal<>();
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).isNotEmpty();
        assertThat(issues).anySatisfy(i -> assertThat(i.severity()).isEqualTo("MAJOR"));
    }

    @Test
    void structuredTaskScopeActivatesVtContext() {
        String code = """
            package com.example;
            import java.util.concurrent.StructuredTaskScope;

            public class Svc {
                public synchronized void handle(String s) {
                    System.out.println(s);
                }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).isNotEmpty();
        assertThat(issues).anySatisfy(i -> assertThat(i.severity()).isEqualTo("CRITICAL"));
    }

    // -------------------------------------------------------------------------
    // False positives — inline, granular
    // -------------------------------------------------------------------------

    @Test
    void synchronizedWithoutVtContextIsNeverFlagged() {
        String code = """
            package com.example;

            public class Svc {
                // No virtual thread context in this file
                public synchronized void handle(String data) {
                    System.out.println(data);
                }
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void collectionsynchronizedListIsNeverFlagged() {
        String code = """
            package com.example;
            import java.util.concurrent.Executors;
            import java.util.Collections;
            import java.util.ArrayList;
            import java.util.List;

            public class Svc {
                private final java.util.concurrent.ExecutorService pool =
                    Executors.newVirtualThreadPerTaskExecutor();

                public List<String> safe() {
                    return Collections.synchronizedList(new ArrayList<>());
                }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        // synchronizedList is a method call, not the keyword — must not appear as CRITICAL
        assertThat(issues).noneMatch(i -> i.severity().equals("CRITICAL") && i.message().contains("synchronized"));
    }

    @Test
    void reentrantLockInVtContextIsNeverFlagged() {
        String code = """
            package com.example;
            import java.util.concurrent.Executors;
            import java.util.concurrent.locks.ReentrantLock;

            public class Svc {
                private final java.util.concurrent.ExecutorService pool =
                    Executors.newVirtualThreadPerTaskExecutor();
                private final ReentrantLock lock = new ReentrantLock();

                public void safe(String data) {
                    lock.lock();
                    try { System.out.println(data); } finally { lock.unlock(); }
                }
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void testAnnotatedMethodWithSyncIsNeverFlagged() {
        String code = """
            package com.example;
            import java.util.concurrent.Executors;

            public class Svc {
                private final java.util.concurrent.ExecutorService pool =
                    Executors.newVirtualThreadPerTaskExecutor();

                @org.junit.jupiter.api.Test
                public void testSync() throws InterruptedException {
                    synchronized (this) {
                        Thread.sleep(10);
                    }
                }
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void postConstructWithSyncIsNeverFlagged() {
        String code = """
            package com.example;
            import jakarta.annotation.PostConstruct;
            import java.util.concurrent.Executors;

            public class Svc {
                private final java.util.concurrent.ExecutorService pool =
                    Executors.newVirtualThreadPerTaskExecutor();

                @PostConstruct
                public synchronized void init() {
                    System.out.println("init");
                }
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void mainMethodWithSyncIsNeverFlagged() {
        String code = """
            package com.example;
            import java.util.concurrent.Executors;

            public class App {
                private static final java.util.concurrent.ExecutorService pool =
                    Executors.newVirtualThreadPerTaskExecutor();

                public static void main(String[] args) throws InterruptedException {
                    synchronized (App.class) {
                        Thread.sleep(10);
                    }
                }
            }
            """;
        assertThat(rule.analyze(FileContent.of("App.java", code))).isEmpty();
    }

    @Test
    void synchronizedInCommentIsNeverFlagged() {
        String code = """
            package com.example;
            import java.util.concurrent.Executors;
            import java.util.concurrent.locks.ReentrantLock;

            public class Svc {
                private final java.util.concurrent.ExecutorService pool =
                    Executors.newVirtualThreadPerTaskExecutor();
                private final ReentrantLock lock = new ReentrantLock();

                public void safe(String data) {
                    // avoid synchronized — it pins the carrier thread
                    lock.lock();
                    try { System.out.println(data); } finally { lock.unlock(); }
                }
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void synchronizedInStringLiteralIsNeverFlagged() {
        String code = """
            package com.example;
            import java.util.concurrent.Executors;
            import java.util.concurrent.locks.ReentrantLock;

            public class Svc {
                private final java.util.concurrent.ExecutorService pool =
                    Executors.newVirtualThreadPerTaskExecutor();
                private final ReentrantLock lock = new ReentrantLock();

                public void log() {
                    String msg = "do not use synchronized with virtual threads";
                    System.out.println(msg);
                }
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void threadSleepOutsideAsyncIsNeverFlaggedAsMajor() {
        String code = """
            package com.example;
            import java.util.concurrent.Executors;

            public class Svc {
                private final java.util.concurrent.ExecutorService pool =
                    Executors.newVirtualThreadPerTaskExecutor();

                public void notAsync() throws InterruptedException {
                    Thread.sleep(100); // not @Async → Detection 3 does not apply
                }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        // sleep outside @Async must not trigger Detection 3 (MAJOR)
        assertThat(issues).noneMatch(i -> i.severity().equals("MAJOR") && i.message().contains("sleep"));
    }

    @Test
    void nonJavaFileIgnored() {
        assertThat(rule.analyze(FileContent.of("config.yml", "synchronized: true"))).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String loadFixture(String name) throws IOException {
        String path = "/fixtures/" + name;
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) throw new IOException("Fixture not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
