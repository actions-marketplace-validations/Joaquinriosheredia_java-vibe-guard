package com.vibeguard.mcp.rules;

import com.vibeguard.mcp.dto.FileContent;
import com.vibeguard.mcp.dto.Issue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Detects blocking calls (.get(), .join(), .block()) used inside @Transactional methods.
 * This causes thread starvation under load because the transaction holds a DB connection
 * while waiting on a Future/Mono, exhausting the connection pool.
 */
@Component
public class TransactionalAsyncRule implements Rule {

    private static final Pattern TRANSACTIONAL_ANNOTATION =
        Pattern.compile("@Transactional(?:al)?\\b");

    private static final Pattern METHOD_OPEN =
        Pattern.compile("(?:public|protected|private)\\s+\\S+\\s+\\w+\\s*\\(");

    private static final Pattern BLOCKING_CALL =
        Pattern.compile("\\.(get|join|block|blockFirst|blockLast)\\s*\\(");

    @Override
    public String id() {
        return "VIBE-001";
    }

    @Override
    public String description() {
        return "Blocking call (.get/.join/.block) inside @Transactional method";
    }

    @Override
    public List<Issue> analyze(FileContent file) {
        if (!file.path().endsWith(".java")) {
            return List.of();
        }

        List<Issue> issues = new ArrayList<>();
        List<String> lines = file.lines();

        boolean inTransactional = false;
        int braceDepth = 0;
        int methodStartDepth = -1;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.strip();

            if (TRANSACTIONAL_ANNOTATION.matcher(trimmed).find()) {
                inTransactional = true;
            }

            if (inTransactional && METHOD_OPEN.matcher(trimmed).find()) {
                methodStartDepth = braceDepth;
            }

            for (char c : line.toCharArray()) {
                if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
            }

            if (methodStartDepth >= 0 && braceDepth <= methodStartDepth && trimmed.contains("}")) {
                inTransactional = false;
                methodStartDepth = -1;
            }

            if (inTransactional && methodStartDepth >= 0 && BLOCKING_CALL.matcher(trimmed).find()) {
                var matcher = BLOCKING_CALL.matcher(trimmed);
                while (matcher.find()) {
                    issues.add(new Issue(
                        id(),
                        "CRITICAL",
                        file.path(),
                        i + 1,
                        "Blocking call '." + matcher.group(1) + "()' inside @Transactional method — " +
                        "holds DB connection while waiting on async result, causing connection pool starvation under load"
                    ));
                }
            }
        }

        return issues;
    }
}
