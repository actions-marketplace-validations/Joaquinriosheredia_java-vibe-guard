package com.vibeguard.mcp.rules;

import com.vibeguard.mcp.dto.FileContent;
import com.vibeguard.mcp.dto.Issue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JpaNPlusOneRuleTest {

    private final JpaNPlusOneRule rule = new JpaNPlusOneRule();

    // -------------------------------------------------------------------------
    // Fixture-based: real files, real patterns
    // -------------------------------------------------------------------------

    @Test
    void detectsAllNPlusOnePatterns() throws IOException {
        String code = loadFixture("JpaNPlusOneTruePositive.java");
        List<Issue> issues = rule.analyze(FileContent.of("JpaNPlusOneTruePositive.java", code));

        assertThat(issues)
            .as("Expected multiple CRITICAL N+1 issues in the true-positive fixture")
            .isNotEmpty();

        assertThat(issues).allSatisfy(issue -> {
            assertThat(issue.ruleId()).isEqualTo("VIBE-003");
            assertThat(issue.severity()).isEqualTo("CRITICAL");
        });
    }

    @Test
    void zeroFalsePositivesInSafeContexts() throws IOException {
        String code = loadFixture("JpaNPlusOneFalsePositive.java");
        List<Issue> issues = rule.analyze(FileContent.of("JpaNPlusOneFalsePositive.java", code));

        assertThat(issues)
            .as("No issues expected: all patterns are safe (batch ops, no-loop, @Test, comments, strings)")
            .isEmpty();
    }

    // -------------------------------------------------------------------------
    // True positives — inline, granular
    // -------------------------------------------------------------------------

    @Test
    void findByIdInForLoopDetected() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import java.util.List;

            @Service
            public class Svc {
                private final UserRepository userRepository;
                public void load(List<Long> ids) {
                    for (Long id : ids) {
                        userRepository.findById(id);
                    }
                }
                interface UserRepository { Object findById(Long id); }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().message()).contains("findById");
    }

    @Test
    void saveInWhileLoopDetected() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import java.util.List;

            @Service
            public class Svc {
                private final OrderRepo orderRepo;
                public void saveAll(List<Object> items) {
                    int i = 0;
                    while (i < items.size()) {
                        orderRepo.save(items.get(i++));
                    }
                }
                interface OrderRepo { void save(Object o); }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().message()).contains("save");
    }

    @Test
    void findByIdInStreamMapDetected() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import java.util.List;

            @Service
            public class Svc {
                private final OrderRepository orderRepository;
                public List<Object> load(List<Long> ids) {
                    return ids.stream()
                        .map(id -> orderRepository.findById(id))
                        .toList();
                }
                interface OrderRepository { Object findById(Long id); }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().message()).contains("findById");
    }

    @Test
    void lazyCollectionSizeInLoopDetected() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import java.util.List;

            @Service
            public class Svc {
                public int countAll(List<User> users) {
                    int total = 0;
                    for (User u : users) {
                        total += u.getOrders().size();
                    }
                    return total;
                }
                static class User { public List<Object> getOrders() { return List.of(); } }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().message()).contains("lazy");
    }

    @Test
    void repoInStreamForEachDetected() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import java.util.List;

            @Service
            public class Svc {
                private final ItemRepository itemRepository;
                public void persist(List<Object> items) {
                    items.stream().forEach(i -> itemRepository.save(i));
                }
                interface ItemRepository { void save(Object o); }
            }
            """;
        List<Issue> issues = rule.analyze(FileContent.of("Svc.java", code));
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().message()).contains("save");
    }

    // -------------------------------------------------------------------------
    // False positives — inline, granular
    // -------------------------------------------------------------------------

    @Test
    void saveAllIsNeverFlagged() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import java.util.List;

            @Service
            public class Svc {
                private final OrderRepository orderRepository;
                public void persist(List<Object> items) {
                    orderRepository.saveAll(items);
                }
                interface OrderRepository { void saveAll(List<Object> o); }
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void findAllByIdIsNeverFlagged() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import java.util.List;

            @Service
            public class Svc {
                private final OrderRepository orderRepository;
                public List<Object> load(List<Long> ids) {
                    return orderRepository.findAllById(ids);
                }
                interface OrderRepository { List<Object> findAllById(List<Long> ids); }
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void findByIdOutsideLoopIsNeverFlagged() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;

            @Service
            public class Svc {
                private final UserRepository userRepository;
                public Object fetch(Long id) {
                    return userRepository.findById(id);
                }
                interface UserRepository { Object findById(Long id); }
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void commentWithFindByIdIsNeverFlagged() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import java.util.List;

            @Service
            public class Svc {
                private final UserRepository userRepository;
                public void load(List<Long> ids) {
                    // userRepository.findById(id) causes N+1 — use findAllById
                    userRepository.findAllById(ids);
                }
                interface UserRepository {
                    Object findById(Long id);
                    List<Object> findAllById(List<Long> ids);
                }
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void stringLiteralWithFindByIdIsNeverFlagged() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import java.util.List;

            @Service
            public class Svc {
                private final OrderRepository orderRepository;
                public void warn(List<Long> ids) {
                    String hint = "avoid orderRepository.findById(id) in loops";
                    orderRepository.findAllById(ids);
                }
                interface OrderRepository { List<Object> findAllById(List<Long> ids); }
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void safeStreamWithoutJpaAccessIsNeverFlagged() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import java.util.List;

            @Service
            public class Svc {
                public List<String> upperCase(List<String> items) {
                    return items.stream()
                        .map(String::toUpperCase)
                        .toList();
                }
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void nonRepositoryVariableInLoopIsNeverFlagged() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import java.util.List;

            @Service
            public class Svc {
                public void transform(List<String> items) {
                    for (String item : items) {
                        String result = mapper.findById(item);
                    }
                }
                static Object mapper = null;
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void testAnnotatedMethodIsNeverFlagged() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import java.util.List;

            @Service
            public class Svc {
                private final OrderRepository orderRepository;
                @Test
                public void testLoad() {
                    for (Long id : List.of(1L, 2L)) {
                        orderRepository.findById(id);
                    }
                }
                interface OrderRepository { Object findById(Long id); }
            }
            """;
        assertThat(rule.analyze(FileContent.of("Svc.java", code))).isEmpty();
    }

    @Test
    void restControllerIsNeverFlagged() {
        String code = """
            package com.example;
            import org.springframework.web.bind.annotation.RestController;
            import java.util.List;

            @RestController
            public class Ctrl {
                private final OrderRepository orderRepository;
                public void load(List<Long> ids) {
                    for (Long id : ids) {
                        orderRepository.findById(id);
                    }
                }
                interface OrderRepository { Object findById(Long id); }
            }
            """;
        assertThat(rule.analyze(FileContent.of("Ctrl.java", code))).isEmpty();
    }

    @Test
    void nonJavaFileIgnored() {
        assertThat(rule.analyze(FileContent.of("config.yml", "findById: true"))).isEmpty();
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
