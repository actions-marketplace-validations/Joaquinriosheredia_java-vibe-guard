package com.vibeguard.mcp.rules;

import com.vibeguard.mcp.dto.FileContent;
import com.vibeguard.mcp.dto.Issue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionPoolStarvationRuleTest {

    private final ConnectionPoolStarvationRule rule = new ConnectionPoolStarvationRule();

    // -------------------------------------------------------------------------
    // Fixture-based: real files, real patterns
    // -------------------------------------------------------------------------

    @Test
    void detectsAllConnectionPoolStarvationPatterns() throws IOException {
        String code = loadFixture("ConnectionPoolStarvationTruePositive.java");
        List<Issue> issues = rule.analyze(FileContent.of("ConnectionPoolStarvationTruePositive.java", code));

        assertThat(issues)
            .as("Expected multiple CRITICAL issues in the true-positive fixture")
            .isNotEmpty();

        assertThat(issues).allSatisfy(issue -> {
            assertThat(issue.ruleId()).isEqualTo("VIBE-005");
            assertThat(issue.severity()).isIn("CRITICAL", "MAJOR");
        });
    }

    @Test
    void zeroFalsePositivesInSafeContexts() throws IOException {
        String code = loadFixture("ConnectionPoolStarvationFalsePositive.java");
        List<Issue> issues = rule.analyze(FileContent.of("ConnectionPoolStarvationFalsePositive.java", code));

        assertThat(issues)
            .as("No issues expected: all patterns are safe (no tx context, readOnly, excluded methods, comments, strings)")
            .isEmpty();
    }

    // -------------------------------------------------------------------------
    // True positives — @Transactional + blocking call → CRITICAL
    // -------------------------------------------------------------------------

    @Test
    void transactionalPlusThreadSleepIsCritical() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import org.springframework.transaction.annotation.Transactional;

            @Service
            public class Svc {
                @Transactional
                public void doWork() throws InterruptedException {
                    Thread.sleep(2000);
                }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().severity()).isEqualTo("CRITICAL");
        assertThat(issues.getFirst().ruleId()).isEqualTo("VIBE-005");
    }

    @Test
    void transactionalPlusRestTemplateIsCritical() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import org.springframework.transaction.annotation.Transactional;
            import org.springframework.web.client.RestTemplate;

            @Service
            public class Svc {
                private final RestTemplate restTemplate;
                Svc(RestTemplate rt) { this.restTemplate = rt; }

                @Transactional
                public String fetch() {
                    return restTemplate.getForObject("http://example.com", String.class);
                }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().severity()).isEqualTo("CRITICAL");
        assertThat(issues.getFirst().message()).contains("blocking call");
    }

    @Test
    void transactionalPlusWebClientBlockIsCritical() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import org.springframework.transaction.annotation.Transactional;
            import org.springframework.web.reactive.function.client.WebClient;

            @Service
            public class Svc {
                @Transactional
                public String fetch(WebClient webClient) {
                    return webClient.get().retrieve().bodyToMono(String.class).block();
                }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().severity()).isEqualTo("CRITICAL");
    }

    @Test
    void transactionalPlusFilesReadAllBytesIsCritical() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import org.springframework.transaction.annotation.Transactional;
            import java.nio.file.Files;
            import java.nio.file.Path;

            @Service
            public class Svc {
                @Transactional
                public byte[] read() throws Exception {
                    return Files.readAllBytes(Path.of("/tmp/data.bin"));
                }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().severity()).isEqualTo("CRITICAL");
    }

    @Test
    void transactionalPlusFutureGetIsCritical() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import org.springframework.transaction.annotation.Transactional;
            import java.util.concurrent.Future;

            @Service
            public class Svc {
                @Transactional
                public String process(Future<String> future) throws Exception {
                    return future.get();
                }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().severity()).isEqualTo("CRITICAL");
    }

    @Test
    void classLevelTransactionalPlusBlockingIsCritical() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import org.springframework.transaction.annotation.Transactional;

            @Service
            @Transactional
            public class Svc {
                public void doWork() throws InterruptedException {
                    Thread.sleep(500);
                }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().severity()).isEqualTo("CRITICAL");
    }

    @Test
    void restControllerWithTransactionalIsMajor() {
        String code = """
            package com.example;
            import org.springframework.web.bind.annotation.RestController;
            import org.springframework.transaction.annotation.Transactional;

            @RestController
            public class Ctrl {
                @Transactional
                public String getUser(Long id) {
                    return "user";
                }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Ctrl.java", code));
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().severity()).isEqualTo("MAJOR");
        assertThat(issues.getFirst().message()).contains("@RestController");
    }

    @Test
    void restControllerWithClassLevelTransactionalIsMajor() {
        String code = """
            package com.example;
            import org.springframework.web.bind.annotation.RestController;
            import org.springframework.transaction.annotation.Transactional;

            @RestController
            @Transactional
            public class Ctrl {
                public String getData() {
                    return "data";
                }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Ctrl.java", code));
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().severity()).isEqualTo("MAJOR");
    }

    // -------------------------------------------------------------------------
    // False positives — inline, granular
    // -------------------------------------------------------------------------

    @Test
    void restTemplateOutsideTransactionalIsNeverFlagged() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import org.springframework.web.client.RestTemplate;

            @Service
            public class Svc {
                public String fetch(RestTemplate restTemplate) {
                    return restTemplate.getForObject("http://example.com", String.class);
                }
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void threadSleepInTestIsNeverFlagged() {
        String code = """
            package com.example;
            import org.springframework.transaction.annotation.Transactional;

            public class Svc {
                @org.junit.jupiter.api.Test
                @Transactional
                public void testSomething() throws InterruptedException {
                    Thread.sleep(50);
                }
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void readOnlyTransactionalWithoutIoIsNeverFlagged() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import org.springframework.transaction.annotation.Transactional;

            @Service
            public class Svc {
                private final UserRepository userRepository;
                Svc(UserRepository r) { this.userRepository = r; }

                @Transactional(readOnly = true)
                public Object findUser(Long id) {
                    return userRepository.findById(id);
                }
                interface UserRepository { Object findById(Long id); }
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void repositorySaveInsideTransactionalIsNeverFlagged() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import org.springframework.transaction.annotation.Transactional;

            @Service
            public class Svc {
                private final OrderRepository orderRepository;
                Svc(OrderRepository r) { this.orderRepository = r; }

                @Transactional
                public void save(Object order) {
                    orderRepository.save(order);
                }
                interface OrderRepository { void save(Object o); }
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void commentWithRestTemplateIsNeverFlagged() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import org.springframework.transaction.annotation.Transactional;

            @Service
            public class Svc {
                private final UserRepository userRepository;
                Svc(UserRepository r) { this.userRepository = r; }

                @Transactional
                public void processUser(Long id) {
                    // avoid restTemplate.getForObject() inside @Transactional — move it outside
                    userRepository.save(null);
                }
                interface UserRepository { void save(Object o); }
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void stringLiteralWithBlockingPatternIsNeverFlagged() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import org.springframework.transaction.annotation.Transactional;

            @Service
            public class Svc {
                @Transactional
                public void log() {
                    String hint = "do not call Thread.sleep() or restTemplate.exchange() inside @Transactional";
                    System.out.println(hint);
                }
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void postConstructWithTransactionalIsNeverFlagged() {
        String code = """
            package com.example;
            import jakarta.annotation.PostConstruct;
            import org.springframework.stereotype.Service;
            import org.springframework.transaction.annotation.Transactional;

            @Service
            public class Svc {
                @PostConstruct
                @Transactional
                public void init() throws InterruptedException {
                    Thread.sleep(10);
                }
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void sleepOutsideTransactionalIsNeverFlagged() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;

            @Service
            public class Svc {
                public void poll() throws InterruptedException {
                    Thread.sleep(100);
                }
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void readOnlyTransactionalInRestControllerIsNeverFlaggedAsMajor() {
        String code = """
            package com.example;
            import org.springframework.web.bind.annotation.RestController;
            import org.springframework.transaction.annotation.Transactional;

            @RestController
            public class Ctrl {
                @Transactional(readOnly = true)
                public String get(Long id) {
                    return "data";
                }
            }
            """;
        assertThat(rule.analyze(FileContent.of("Ctrl.java", code))).isEmpty();
    }

    @Test
    void nonJavaFileIgnored() {
        assertThat(rule.analyze(FileContent.of("config.yml", "@Transactional: true"))).isEmpty();
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
