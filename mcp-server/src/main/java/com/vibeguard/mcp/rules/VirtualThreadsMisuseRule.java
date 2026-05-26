package com.vibeguard.mcp.rules;

import com.vibeguard.mcp.dto.FileContent;
import com.vibeguard.mcp.dto.Issue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Detects patterns that degrade or break the Virtual Threads (JEP 425/444) model in Java 21.
 *
 * Detection requires EXPLICIT virtual thread usage in the same file (Phase 1 pre-scan).
 * This prevents false positives in traditional thread-pool codebases.
 *
 * Signal classes:
 *   CRITICAL — synchronized keyword in VT context (pins carrier platform thread)
 *   CRITICAL — blocking I/O inside synchronized context (pinning: carrier blocked under monitor)
 *   MAJOR   — ThreadLocal creation in VT context (memory leak at scale with large VT pools)
 *   MAJOR   — Thread.sleep() in @Async method in VT context (blocks underlying thread)
 *
 * Explicit exclusions:
 *   - synchronized not present → not flagged (Collections.synchronizedList is a method name,
 *     not the keyword — \bsynchronized\b word boundary handles this automatically)
 *   - ReentrantLock, Atomic*, explicit locks → not flagged (correct VT alternatives)
 *   - @Test / @PostConstruct / main() → excluded
 *   - No VT context in file → all detections disabled
 *   - Patterns in // comments or string literals → stripped by codeOnly()
 */
@Component
public class VirtualThreadsMisuseRule implements Rule {

    // Phase 1: explicit VT usage indicators — must appear in the file to enable detection.
    // We do NOT infer VT usage from framework config (e.g. spring.threads.virtual.enabled).
    // Only explicit API calls in this file trigger the rule.
    private static final Pattern VT_CONTEXT = Pattern.compile(
        "newVirtualThreadPerTaskExecutor\\s*\\(" +
        "|Thread\\.ofVirtual\\b" +
        "|StructuredTaskScope\\b" +
        "|newThreadPerTaskExecutor"
    );

    // Method boundary patterns (same as other rules)
    private static final Pattern EXCLUDED_METHOD_ANNOTATION =
        Pattern.compile("@(?:\\w+\\.)*(?:Test|PostConstruct)\\b");

    private static final Pattern ASYNC_ANNOTATION =
        Pattern.compile("@(?:\\w+\\.)*Async\\b");

    private static final Pattern MAIN_METHOD =
        Pattern.compile("public\\s+static\\s+void\\s+main\\s*\\(");

    private static final Pattern METHOD_OPEN = Pattern.compile(
        "(?:public|protected|private)" +
        "(?:\\s+(?:static|final|synchronized|abstract|native))*" +
        "\\s+\\S+\\s+\\w+\\s*\\("
    );

    // \bsynchronized\b — word boundary guarantees this matches the Java keyword only.
    // Collections.synchronizedList / synchronizedMap etc. are single camelCase identifiers
    // with no word boundary between 'd' and the next letter, so they never match.
    private static final Pattern SYNC_KEYWORD = Pattern.compile("\\bsynchronized\\b");

    // synchronized block: keyword immediately followed by '(' — distinguishes it from
    // method modifiers like "public synchronized void foo()" where a return type follows.
    private static final Pattern SYNC_BLOCK = Pattern.compile("\\bsynchronized\\s*\\(");

    // ThreadLocal creation sites only — avoids false positives from threadLocal.get()/set()
    // calls on arbitrary variables. Creation sites are the definitive signal of intent.
    private static final Pattern THREAD_LOCAL_CREATE = Pattern.compile(
        "\\bnew\\s+(?:Inheritable)?ThreadLocal\\s*[<(]" +
        "|\\bThreadLocal\\.withInitial\\s*\\("
    );

    private static final Pattern THREAD_SLEEP = Pattern.compile("\\bThread\\.sleep\\s*\\(");

    // Blocking operations that cause carrier-thread pinning inside synchronized context.
    // When a virtual thread is mounted on a carrier and hits one of these while holding
    // a monitor, the JVM cannot unmount it — the carrier is blocked for the duration.
    private static final Pattern PINNING_IO = Pattern.compile(
        "\\bThread\\.sleep\\s*\\(" +
        "|\\brestTemplate\\.(?:getForObject|getForEntity|postForObject|postForEntity|exchange|execute)\\s*\\(" +
        "|\\bjdbcTemplate\\." +
        "|\\bconnection\\.(?:prepare|create)\\w*\\s*\\(" +
        "|\\bstatement\\.execute\\w*\\s*\\(" +
        "|\\brs\\.next\\s*\\(" +
        "|\\bFiles\\.read(?:All|String|Bytes|AllLines)\\s*\\(" +
        "|\\binputStream\\.read\\s*\\(" +
        "|\\bsocket\\.getInputStream\\s*\\(" +
        "|\\bfuture\\.get\\s*\\(|\\bFuture\\.get\\s*\\("
    );

    @Override
    public String id() { return "VIBE-004"; }

    @Override
    public String description() {
        return "Virtual threads misuse: synchronized/ThreadLocal/pinning patterns degrade VT performance";
    }

    @Override
    public List<Issue> analyze(FileContent file) {
        if (!file.path().endsWith(".java")) return List.of();

        List<String> lines = file.lines();

        // Phase 1 — pre-scan: does this file explicitly use virtual threads?
        // If not, skip all detection. This is the primary false-positive gate:
        // synchronized in a traditional thread-pool service is NOT our concern.
        boolean hasVtContext = lines.stream()
            .map(l -> codeOnly(l.strip()))
            .anyMatch(code -> VT_CONTEXT.matcher(code).find());

        if (!hasVtContext) return List.of();

        // Phase 2 — line-by-line scan with method and synchronized-block tracking
        List<Issue> issues = new ArrayList<>();
        int braceDepth = 0;

        boolean pendingExcluded = false;
        boolean pendingAsync    = false;
        boolean inMethod        = false;
        boolean inExcludedMethod = false;
        boolean inAsyncMethod   = false;
        boolean methodIsSynchronized = false;
        int     methodDepth     = -1;

        // Tracks synchronized (expr) { ... } blocks for pinning detection
        boolean inSyncBlock    = false;
        int     syncBlockDepth = -1;

        for (int i = 0; i < lines.size(); i++) {
            String line  = lines.get(i);
            String trim  = line.strip();
            String code  = codeOnly(trim);

            // --- Annotation tracking ---
            if (EXCLUDED_METHOD_ANNOTATION.matcher(code).find()) pendingExcluded = true;
            if (ASYNC_ANNOTATION.matcher(code).find())           pendingAsync    = true;

            // --- Method entry ---
            if (!inMethod && METHOD_OPEN.matcher(code).find()) {
                boolean isAbstract = !code.contains("{") && code.endsWith(";");
                if (!isAbstract) {
                    inMethod              = true;
                    inExcludedMethod      = pendingExcluded || MAIN_METHOD.matcher(code).find();
                    inAsyncMethod         = pendingAsync;
                    methodIsSynchronized  = SYNC_KEYWORD.matcher(code).find();
                    methodDepth           = braceDepth;
                    inSyncBlock           = false;
                    syncBlockDepth        = -1;
                }
                pendingExcluded = false;
                pendingAsync    = false;
            }

            // --- Synchronized block entry (inside active, non-excluded method) ---
            // SYNC_BLOCK matches "synchronized(" — method modifiers have a return type
            // between "synchronized" and "(" so they don't satisfy \bsynchronized\s*\(
            if (inMethod && !inExcludedMethod && !methodIsSynchronized
                    && !inSyncBlock && SYNC_BLOCK.matcher(code).find()) {
                inSyncBlock    = true;
                syncBlockDepth = braceDepth; // depth before this line's { is counted
            }

            // --- Brace counting ---
            for (char c : line.toCharArray()) {
                if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
            }

            // --- Capture synchronized context (post-brace, pre-exit) ---
            // methodIsSynchronized: entire method body is synchronized context
            // inSyncBlock: only the block body (braceDepth > syncBlockDepth)
            boolean inSyncContext = methodIsSynchronized
                || (inSyncBlock && braceDepth > syncBlockDepth);

            // --- Exit: synchronized block ---
            if (inSyncBlock && braceDepth <= syncBlockDepth && trim.contains("}")) {
                inSyncBlock    = false;
                syncBlockDepth = -1;
            }

            // --- Exit: method ---
            if (inMethod && braceDepth <= methodDepth && trim.contains("}")) {
                inMethod              = false;
                inExcludedMethod      = false;
                inAsyncMethod         = false;
                methodIsSynchronized  = false;
                inSyncBlock           = false;
                methodDepth           = -1;
            }

            // Skip excluded methods entirely
            if (inExcludedMethod) continue;

            boolean inActiveMethod = inMethod;

            // --- Detection 1: synchronized keyword in VT context → CRITICAL ---
            // Covers both synchronized methods and synchronized blocks.
            // The carrier platform thread cannot be unmounted while holding a monitor.
            if (inActiveMethod && SYNC_KEYWORD.matcher(code).find()) {
                issues.add(new Issue(id(), "CRITICAL", file.path(), i + 1,
                    "Virtual threads misuse: 'synchronized' pins the carrier platform thread — " +
                    "replace with ReentrantLock, StampedLock, or java.util.concurrent primitives"
                ));
            }

            // --- Detection 2: ThreadLocal creation in VT context → MAJOR ---
            // Checked at any scope (class fields and method bodies).
            // With millions of virtual threads, each ThreadLocal entry leaks until the VT exits.
            // Also applies outside method bodies (field declarations), so no inActiveMethod guard.
            if (!inExcludedMethod && THREAD_LOCAL_CREATE.matcher(code).find()) {
                issues.add(new Issue(id(), "MAJOR", file.path(), i + 1,
                    "Virtual threads misuse: ThreadLocal leaks memory with large VT pools — " +
                    "prefer ScopedValue (JEP 446) or pass state explicitly through parameters"
                ));
            }

            // --- Detection 3: Thread.sleep() in @Async method in VT context → MAJOR ---
            // If the @Async executor is a VT pool, sleep() blocks the carrier; if it is
            // platform threads, sleep() holds a pool thread. Either way it is a smell here.
            if (inActiveMethod && inAsyncMethod && THREAD_SLEEP.matcher(code).find()) {
                issues.add(new Issue(id(), "MAJOR", file.path(), i + 1,
                    "Virtual threads misuse: Thread.sleep() in @Async method blocks the carrier thread — " +
                    "use a VT executor and replace busy-wait/sleep with reactive delay or task resubmission"
                ));
            }

            // --- Detection 4: blocking I/O inside synchronized context → CRITICAL (pinning) ---
            // The virtual thread cannot unmount from the carrier while it holds a monitor.
            // Any blocking call here means the carrier is stuck for the full I/O duration.
            if (inActiveMethod && inSyncContext && PINNING_IO.matcher(code).find()) {
                issues.add(new Issue(id(), "CRITICAL", file.path(), i + 1,
                    "Pinning risk: blocking I/O inside synchronized context prevents VT unmounting — " +
                    "the carrier platform thread is blocked for the full I/O duration; " +
                    "remove synchronized or use non-blocking I/O"
                ));
            }
        }

        return issues;
    }

    /**
     * Strips // line comments and string literal contents before pattern matching.
     * Prevents false positives from patterns that appear in comments or string values.
     * Known limitation: does not handle escaped quotes inside string literals.
     */
    private static String codeOnly(String trimmed) {
        int commentIdx = trimmed.indexOf("//");
        String noComment = commentIdx >= 0 ? trimmed.substring(0, commentIdx) : trimmed;
        return noComment.replaceAll("\"[^\"]*\"", "\"\"");
    }
}
