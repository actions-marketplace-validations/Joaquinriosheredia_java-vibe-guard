package com.vibeguard.mcp.rules;

import com.vibeguard.mcp.dto.FileContent;
import com.vibeguard.mcp.dto.Issue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReactorBlockingCallRuleTest {

    private final ReactorBlockingCallRule rule = new ReactorBlockingCallRule();

    // -------------------------------------------------------------------------
    // True positive: 4 blocking patterns in a @RestController
    // -------------------------------------------------------------------------

    @Test
    void detectsAllBlockingPatternsInRestController() throws IOException {
        String code = loadFixture("ReactorBlockingTruePositive.java");
        List<Issue> issues = rule.analyze(FileContent.of("ReactorBlockingTruePositive.java", code));

        assertThat(issues)
            .as("Expected exactly 4 CRITICAL issues — one per blocking pattern")
            .hasSize(4);

        assertThat(issues).allSatisfy(issue -> {
            assertThat(issue.ruleId()).isEqualTo("VIBE-002");
            assertThat(issue.severity()).isEqualTo("CRITICAL");
        });

        List<String> messages = issues.stream().map(Issue::message).toList();
        assertThat(messages).anyMatch(m -> m.contains(".block()"));
        assertThat(messages).anyMatch(m -> m.contains(".blockFirst()"));
        assertThat(messages).anyMatch(m -> m.contains(".blockLast()"));
        assertThat(messages).anyMatch(m -> m.contains(".toFuture().get()"));
    }

    // -------------------------------------------------------------------------
    // False positives: @Test, @PostConstruct, main(), variable named "block"
    // -------------------------------------------------------------------------

    @Test
    void zeroFalsePositivesInSafeContexts() throws IOException {
        String code = loadFixture("ReactorBlockingFalsePositive.java");
        List<Issue> issues = rule.analyze(FileContent.of("ReactorBlockingFalsePositive.java", code));

        assertThat(issues)
            .as("No issues expected: all .block() calls are in @Test, @PostConstruct, main(), or are variable names")
            .isEmpty();
    }

    // -------------------------------------------------------------------------
    // Granular cases (inline code — no file I/O needed)
    // -------------------------------------------------------------------------

    @Test
    void blockInServiceIsDetected() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import reactor.core.publisher.Mono;

            @Service
            public class MyService {
                public String fetchBlocking() {
                    return Mono.just("data").block();
                }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("MyService.java", code));
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().message()).contains(".block()");
    }

    @Test
    void blockInComponentIsDetected() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Component;
            import reactor.core.publisher.Flux;

            @Component
            public class MyComponent {
                public String first() {
                    return Flux.just("a", "b").blockFirst();
                }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("MyComponent.java", code));
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().message()).contains(".blockFirst()");
    }

    @Test
    void blockInPlainClassNotDetected() {
        String code = """
            package com.example;
            import reactor.core.publisher.Mono;

            public class NotABean {
                public String fetch() {
                    return Mono.just("data").block();
                }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("NotABean.java", code));
        assertThat(issues).isEmpty();
    }

    @Test
    void variableNamedBlockNotDetected() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;

            @Service
            public class QueueService {
                public void process() {
                    boolean block = shouldBlock();
                    int blockSize = 50;
                    System.out.println(block + " " + blockSize);
                }
                private boolean shouldBlock() { return false; }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("QueueService.java", code));
        assertThat(issues).isEmpty();
    }

    @Test
    void postConstructNotDetected() {
        String code = """
            package com.example;
            import jakarta.annotation.PostConstruct;
            import org.springframework.stereotype.Service;
            import reactor.core.publisher.Mono;

            @Service
            public class InitService {
                @PostConstruct
                public void init() {
                    String cfg = Mono.just("cfg").block();
                }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("InitService.java", code));
        assertThat(issues).isEmpty();
    }

    @Test
    void mainMethodNotDetected() {
        String code = """
            package com.example;
            import org.springframework.web.bind.annotation.RestController;
            import reactor.core.publisher.Mono;

            @RestController
            public class App {
                public static void main(String[] args) {
                    String v = Mono.just("startup").block();
                }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("App.java", code));
        assertThat(issues).isEmpty();
    }

    @Test
    void nonJavaFileIgnored() {
        List<Issue> issues = rule.analyze(FileContent.of("config.yml", "block: true"));
        assertThat(issues).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String loadFixture(String name) throws IOException {
        String path = "/fixtures/" + name;
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) throw new IOException("Fixture not found on classpath: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
