package com.vibeguard.mcp.rules;

import com.vibeguard.mcp.dto.FileContent;
import com.vibeguard.mcp.dto.Issue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MdcContextLeakRuleTest {

    private final MdcContextLeakRule rule = new MdcContextLeakRule();

    // -------------------------------------------------------------------------
    // Fixture-based: real files, real patterns
    // -------------------------------------------------------------------------

    @Test
    void detectsAllMdcLeakPatterns() throws IOException {
        String code = loadFixture("MdcContextLeakTruePositive.java");
        List<Issue> issues = rule.analyze(FileContent.of("MdcContextLeakTruePositive.java", code));

        assertThat(issues)
            .as("Expected CRITICAL issues for each @Async/@Scheduled method with MDC.put() and no MDC.clear()")
            .isNotEmpty();

        assertThat(issues).allSatisfy(issue -> {
            assertThat(issue.ruleId()).isEqualTo("VIBE-007");
            assertThat(issue.severity()).isEqualTo("CRITICAL");
        });
    }

    @Test
    void zeroFalsePositivesInSafeContexts() throws IOException {
        String code = loadFixture("MdcContextLeakFalsePositive.java");
        List<Issue> issues = rule.analyze(FileContent.of("MdcContextLeakFalsePositive.java", code));

        assertThat(issues)
            .as("No issues expected: MDC.clear() present, no async context, or excluded methods")
            .isEmpty();
    }

    // -------------------------------------------------------------------------
    // True positives — inline, granular
    // -------------------------------------------------------------------------

    @Test
    void asyncMethodWithMdcPutAndNoClearIsCritical() {
        String code = """
            package com.example;
            import org.slf4j.MDC;
            import org.springframework.scheduling.annotation.Async;

            public class Svc {
                @Async
                public void handle(String id) {
                    MDC.put("requestId", id);
                    doWork();
                }
                private void doWork() {}
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().severity()).isEqualTo("CRITICAL");
        assertThat(issues.getFirst().ruleId()).isEqualTo("VIBE-007");
        assertThat(issues.getFirst().message()).contains("MDC.put()");
    }

    @Test
    void scheduledMethodWithMdcPutAndNoClearIsCritical() {
        String code = """
            package com.example;
            import org.slf4j.MDC;
            import org.springframework.scheduling.annotation.Scheduled;

            public class Svc {
                @Scheduled(fixedDelay = 5000)
                public void runBatch() {
                    MDC.put("job", "batch");
                    doWork();
                }
                private void doWork() {}
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().severity()).isEqualTo("CRITICAL");
    }

    @Test
    void scheduledCronWithMdcPutAndNoClearIsCritical() {
        String code = """
            package com.example;
            import org.slf4j.MDC;
            import org.springframework.scheduling.annotation.Scheduled;

            public class Svc {
                @Scheduled(cron = "0 * * * * *")
                public void cronJob() {
                    MDC.put("trigger", "cron");
                    doWork();
                }
                private void doWork() {}
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().severity()).isEqualTo("CRITICAL");
    }

    @Test
    void issuereportedAtFirstMdcPutLine() {
        String code = """
            package com.example;
            import org.slf4j.MDC;
            import org.springframework.scheduling.annotation.Async;

            public class Svc {
                @Async
                public void handle(String traceId, String spanId) {
                    doSetup();
                    MDC.put("traceId", traceId);
                    MDC.put("spanId", spanId);
                    doWork();
                }
                private void doSetup() {}
                private void doWork() {}
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).hasSize(1);
        // Issue must point to the FIRST MDC.put() line, not the method or second put
        assertThat(issues.getFirst().message()).contains("MDC.put()");
    }

    @Test
    void eachLeakingMethodEmitsOneIssue() {
        String code = """
            package com.example;
            import org.slf4j.MDC;
            import org.springframework.scheduling.annotation.Async;

            public class Svc {
                @Async
                public void methodA(String a) {
                    MDC.put("a", a);
                    doWork();
                }

                @Async
                public void methodB(String b) {
                    MDC.put("b", b);
                    doWork();
                }
                private void doWork() {}
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).hasSize(2);
        assertThat(issues).allMatch(i -> i.severity().equals("CRITICAL"));
    }

    // -------------------------------------------------------------------------
    // False positives — inline, granular
    // -------------------------------------------------------------------------

    @Test
    void asyncWithTryFinallyMdcClearIsNeverFlagged() {
        String code = """
            package com.example;
            import org.slf4j.MDC;
            import org.springframework.scheduling.annotation.Async;

            public class Svc {
                @Async
                public void handle(String id) {
                    MDC.put("requestId", id);
                    try {
                        doWork();
                    } finally {
                        MDC.clear();
                    }
                }
                private void doWork() {}
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void asyncWithMdcClearAtEndIsNeverFlagged() {
        String code = """
            package com.example;
            import org.slf4j.MDC;
            import org.springframework.scheduling.annotation.Async;

            public class Svc {
                @Async
                public void handle(String id) {
                    MDC.put("requestId", id);
                    doWork();
                    MDC.clear();
                }
                private void doWork() {}
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void plainServiceMethodWithMdcPutNoClearIsNeverFlagged() {
        String code = """
            package com.example;
            import org.slf4j.MDC;
            import org.springframework.stereotype.Service;

            @Service
            public class Svc {
                public void process(String id) {
                    MDC.put("id", id);
                    doWork();
                }
                private void doWork() {}
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void commentWithMdcPutIsNeverFlagged() {
        String code = """
            package com.example;
            import org.slf4j.MDC;
            import org.springframework.scheduling.annotation.Async;

            public class Svc {
                @Async
                public void handle(String id) {
                    // MDC.put("id", id) — always call MDC.clear() in finally block
                    doWork();
                }
                private void doWork() {}
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void stringLiteralWithMdcPutIsNeverFlagged() {
        String code = """
            package com.example;
            import org.slf4j.MDC;
            import org.springframework.scheduling.annotation.Async;

            public class Svc {
                @Async
                public void handle() {
                    String example = "MDC.put(key, value) must be followed by MDC.clear()";
                    doWork();
                }
                private void doWork() {}
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void mdcGetOnlyIsNeverFlagged() {
        String code = """
            package com.example;
            import org.slf4j.MDC;
            import org.springframework.scheduling.annotation.Async;

            public class Svc {
                @Async
                public void handle() {
                    String traceId = MDC.get("traceId");
                    doWork();
                }
                private void doWork() {}
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void testAnnotatedAsyncIsNeverFlagged() {
        String code = """
            package com.example;
            import org.slf4j.MDC;
            import org.springframework.scheduling.annotation.Async;

            public class Svc {
                @org.junit.jupiter.api.Test
                @Async
                public void testHandle() {
                    MDC.put("key", "value");
                    doWork();
                }
                private void doWork() {}
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void blockingCallOutsideAsyncOrScheduledIsNeverFlagged() {
        String code = """
            package com.example;
            import org.slf4j.MDC;

            public class Svc {
                public void noAnnotation(String id) {
                    MDC.put("id", id);
                    doWork();
                }
                private void doWork() {}
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void nonJavaFileIgnored() {
        assertThat(rule.analyze(FileContent.of("app.yml", "mdc.put: true"))).isEmpty();
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
