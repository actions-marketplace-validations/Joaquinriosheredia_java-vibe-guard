package com.vibeguard.mcp.rules;

import com.vibeguard.mcp.dto.FileContent;
import com.vibeguard.mcp.dto.Issue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Detects MDC context leaks in thread-pool-managed methods.
 *
 * Thread pools reuse threads. If a method calls MDC.put() without a corresponding
 * MDC.clear(), the next task scheduled on the same thread inherits stale MDC entries
 * from the previous execution — causing incorrect correlation IDs in logs.
 *
 * Gate: ONLY @Async and @Scheduled methods are checked.
 * Generic @Service / @RestController methods are explicitly excluded — the noise-to-signal
 * ratio is too high without knowing whether the method runs on a pooled thread.
 *
 * Detection strategy (deferred emit):
 *   Unlike other rules that emit inline, this rule scans the ENTIRE method body first,
 *   then emits at method exit. A method is flagged only when ALL three conditions hold:
 *     1. The method carries @Async or @Scheduled
 *     2. MDC.put() appears in the method body (code, not comment/string)
 *     3. MDC.clear() does NOT appear anywhere in the method body
 *
 *   The issue is reported at the first MDC.put() line so the developer sees exactly
 *   which put() lacks a corresponding clear.
 *
 * Explicit exclusions:
 *   - @Test / @PostConstruct / main() → never flagged
 *   - MDC.clear() anywhere in method → suppresses the issue
 *   - MDC.get() / MDC.remove() without put → not flagged
 *   - Generic service methods without @Async / @Scheduled → not flagged
 *   - Patterns in // comments or string literals → stripped by codeOnly()
 */
@Component
public class MdcContextLeakRule implements Rule {

    private static final Pattern ASYNC_ANNOTATION =
        Pattern.compile("@(?:\\w+\\.)*Async\\b");

    private static final Pattern SCHEDULED_ANNOTATION =
        Pattern.compile("@(?:\\w+\\.)*Scheduled\\b");

    private static final Pattern EXCLUDED_METHOD_ANNOTATION =
        Pattern.compile("@(?:\\w+\\.)*(?:Test|PostConstruct)\\b");

    private static final Pattern MAIN_METHOD =
        Pattern.compile("public\\s+static\\s+void\\s+main\\s*\\(");

    private static final Pattern METHOD_OPEN = Pattern.compile(
        "(?:public|protected|private)" +
        "(?:\\s+(?:static|final|synchronized|abstract|native))*" +
        "\\s+\\S+\\s+\\w+\\s*\\("
    );

    // Matches both MDC.put( and org.slf4j.MDC.put( — \b before MDC handles the suffix dot
    private static final Pattern MDC_PUT   = Pattern.compile("\\bMDC\\.put\\s*\\(");
    private static final Pattern MDC_CLEAR = Pattern.compile("\\bMDC\\.clear\\s*\\(");

    @Override
    public String id() { return "VIBE-007"; }

    @Override
    public String description() {
        return "MDC context leak: MDC.put() in @Async/@Scheduled without MDC.clear()";
    }

    @Override
    public List<Issue> analyze(FileContent file) {
        if (!file.path().endsWith(".java")) return List.of();

        List<Issue> issues = new ArrayList<>();
        List<String> lines = file.lines();

        int braceDepth = 0;

        // Pending annotation flags (reset at each method entry)
        boolean pendingExcluded  = false;
        boolean pendingAsync     = false;
        boolean pendingScheduled = false;

        // Method-level state
        boolean inMethod                 = false;
        boolean inExcludedMethod         = false;
        boolean isAsyncOrScheduled       = false;
        boolean methodHasMdcPut          = false;
        boolean methodHasMdcClear        = false;
        int     firstMdcPutLine          = -1;
        int     methodDepth              = -1;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trim = line.strip();
            String code = codeOnly(trim);

            // --- Annotation tracking (before method entry) ---
            if (!inMethod) {
                if (EXCLUDED_METHOD_ANNOTATION.matcher(code).find()) pendingExcluded  = true;
                if (ASYNC_ANNOTATION.matcher(code).find())           pendingAsync     = true;
                if (SCHEDULED_ANNOTATION.matcher(code).find())       pendingScheduled = true;
            }

            // --- Method entry ---
            if (!inMethod && METHOD_OPEN.matcher(code).find()) {
                boolean isAbstract = !code.contains("{") && code.endsWith(";");
                if (!isAbstract) {
                    inMethod           = true;
                    inExcludedMethod   = pendingExcluded || MAIN_METHOD.matcher(code).find();
                    isAsyncOrScheduled = pendingAsync || pendingScheduled;
                    methodHasMdcPut    = false;
                    methodHasMdcClear  = false;
                    firstMdcPutLine    = -1;
                    methodDepth        = braceDepth;
                }
                pendingExcluded  = false;
                pendingAsync     = false;
                pendingScheduled = false;
            }

            // --- Brace counting ---
            for (char c : line.toCharArray()) {
                if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
            }

            // --- MDC tracking inside eligible method bodies ---
            if (inMethod && !inExcludedMethod && isAsyncOrScheduled) {
                if (MDC_PUT.matcher(code).find()) {
                    methodHasMdcPut = true;
                    if (firstMdcPutLine < 0) firstMdcPutLine = i + 1;
                }
                if (MDC_CLEAR.matcher(code).find()) {
                    methodHasMdcClear = true;
                }
            }

            // --- Exit: method ---
            // Emit is deferred to method exit so we can evaluate the ENTIRE body.
            // A method is flagged only if it has MDC.put() AND no MDC.clear() anywhere.
            if (inMethod && braceDepth <= methodDepth && trim.contains("}")) {
                if (!inExcludedMethod && isAsyncOrScheduled
                        && methodHasMdcPut && !methodHasMdcClear) {
                    issues.add(new Issue(id(), "CRITICAL", file.path(), firstMdcPutLine,
                        "MDC context leak: MDC.put() in @Async/@Scheduled without MDC.clear() — " +
                        "thread pool threads reuse MDC entries across executions, " +
                        "corrupting log correlation IDs; " +
                        "wrap MDC setup in try { ... } finally { MDC.clear(); }"
                    ));
                }
                inMethod           = false;
                inExcludedMethod   = false;
                isAsyncOrScheduled = false;
                methodHasMdcPut    = false;
                methodHasMdcClear  = false;
                firstMdcPutLine    = -1;
                methodDepth        = -1;
            }
        }

        return issues;
    }

    /**
     * Strips // line comments and string literal contents before pattern matching.
     */
    private static String codeOnly(String trimmed) {
        int commentIdx = trimmed.indexOf("//");
        String noComment = commentIdx >= 0 ? trimmed.substring(0, commentIdx) : trimmed;
        return noComment.replaceAll("\"[^\"]*\"", "\"\"");
    }
}
