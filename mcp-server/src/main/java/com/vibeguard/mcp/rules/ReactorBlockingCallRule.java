package com.vibeguard.mcp.rules;

import com.vibeguard.mcp.dto.FileContent;
import com.vibeguard.mcp.dto.Issue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects reactive blocking calls (.block, .blockFirst, .blockLast, .toFuture().get)
 * inside Spring-managed beans (@RestController, @Service, @Component).
 *
 * Blocking in these contexts pins a carrier thread in the Tomcat/Netty pool, causing
 * latency spikes and throughput collapse under load.
 *
 * Safe exclusions (no issue reported):
 *   - @Test methods        — test assertions need a concrete value
 *   - @PostConstruct       — initialization runs once at startup, blocking is acceptable
 *   - main()               — startup entry point, not a request handler
 *   - Variables named "block" — no leading dot → not a reactive method call
 */
@Component
public class ReactorBlockingCallRule implements Rule {

    // Supports fully qualified form: @org.springframework.web.bind.annotation.RestController
    private static final Pattern REACTIVE_CLASS_ANNOTATION =
        Pattern.compile("@(?:\\w+\\.)*(?:RestController|Service|Component)\\b");

    private static final Pattern CLASS_DECL =
        Pattern.compile("\\bclass\\s+\\w+");

    // Supports fully qualified form: @org.junit.jupiter.api.Test
    private static final Pattern EXCLUDED_METHOD_ANNOTATION =
        Pattern.compile("@(?:\\w+\\.)*(?:Test|PostConstruct)\\b");

    private static final Pattern MAIN_METHOD =
        Pattern.compile("public\\s+static\\s+void\\s+main\\s*\\(");

    // Handles optional modifiers between visibility and return type: static, final, synchronized, etc.
    private static final Pattern METHOD_OPEN =
        Pattern.compile(
            "(?:public|protected|private)" +
            "(?:\\s+(?:static|final|synchronized|abstract|native))*" +
            "\\s+\\S+\\s+\\w+\\s*\\("
        );

    // Leading dot guarantees this is a method call, not a variable named "block"
    private static final Pattern BLOCKING_CALL =
        Pattern.compile("\\.\\s*(block|blockFirst|blockLast)\\s*\\(");

    // .toFuture().get() on a single line — the canonical reactive-to-blocking escape hatch
    private static final Pattern TOFUTURE_GET =
        Pattern.compile("\\.toFuture\\s*\\(\\s*\\)\\s*\\.get\\s*\\(");

    @Override
    public String id() {
        return "VIBE-002";
    }

    @Override
    public String description() {
        return "Reactive blocking call (.block/.blockFirst/.blockLast/.toFuture().get) in Spring-managed bean";
    }

    @Override
    public List<Issue> analyze(FileContent file) {
        if (!file.path().endsWith(".java")) {
            return List.of();
        }

        List<Issue> issues = new ArrayList<>();
        List<String> lines = file.lines();

        int braceDepth = 0;

        boolean pendingReactive = false;   // saw reactive annotation, awaiting class decl
        boolean inReactiveClass = false;
        int classDepth = -1;               // braceDepth when class body opened

        boolean pendingExcluded = false;   // saw @Test/@PostConstruct, awaiting method decl
        boolean inMethod = false;
        boolean inExcludedMethod = false;
        int methodDepth = -1;              // braceDepth when method signature was seen

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.strip();

            // Strip inline // comment to avoid matching annotations or patterns inside comments.
            // Note: this heuristic may incorrectly strip // inside string literals, but that
            // is an acceptable trade-off for a regex-based scanner.
            String code = codeOnly(trimmed);

            // --- Annotation tracking (code portion only, not comment text) ---
            if (REACTIVE_CLASS_ANNOTATION.matcher(code).find()) {
                pendingReactive = true;
            }
            if (EXCLUDED_METHOD_ANNOTATION.matcher(code).find()) {
                pendingExcluded = true;
            }

            // --- Class entry ---
            if (CLASS_DECL.matcher(code).find()) {
                if (pendingReactive) {
                    inReactiveClass = true;
                    classDepth = braceDepth;
                }
                pendingReactive = false;
            }

            // --- Method entry (only inside reactive class, not already tracking one) ---
            if (inReactiveClass && !inMethod && METHOD_OPEN.matcher(code).find()) {
                // Abstract/interface method: no body — line ends with ; and contains no {
                boolean isAbstract = !code.contains("{") && code.endsWith(";");
                if (!isAbstract) {
                    inMethod = true;
                    inExcludedMethod = pendingExcluded || MAIN_METHOD.matcher(code).find();
                    methodDepth = braceDepth;
                }
                pendingExcluded = false; // always consumed by the method signature
            }

            // --- Brace counting (whole line, including string literals) ---
            for (char c : line.toCharArray()) {
                if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
            }

            // --- Method exit ---
            if (inMethod && braceDepth <= methodDepth && trimmed.contains("}")) {
                inMethod = false;
                inExcludedMethod = false;
                methodDepth = -1;
            }

            // --- Class exit ---
            if (inReactiveClass && braceDepth <= classDepth && trimmed.contains("}")) {
                inReactiveClass = false;
                classDepth = -1;
            }

            // --- Blocking call detection (code portion only — no comment false positives) ---
            if (!inReactiveClass || !inMethod || inExcludedMethod) {
                continue;
            }

            Matcher blockMatcher = BLOCKING_CALL.matcher(code);
            while (blockMatcher.find()) {
                issues.add(new Issue(
                    id(),
                    "CRITICAL",
                    file.path(),
                    i + 1,
                    "Reactive blocking call '." + blockMatcher.group(1) + "()' inside Spring bean — " +
                    "pins a thread under load; use reactive composition (.flatMap, .map, .then) instead"
                ));
            }

            if (TOFUTURE_GET.matcher(code).find()) {
                issues.add(new Issue(
                    id(),
                    "CRITICAL",
                    file.path(),
                    i + 1,
                    "Reactive chain '.toFuture().get()' blocks the calling thread — " +
                    "stay on the reactive pipeline with .flatMap() or .subscribe()"
                ));
            }
        }

        return issues;
    }

    /** Returns the code portion of a line, stripping everything from // onward. */
    private static String codeOnly(String trimmed) {
        int idx = trimmed.indexOf("//");
        return idx >= 0 ? trimmed.substring(0, idx) : trimmed;
    }
}
