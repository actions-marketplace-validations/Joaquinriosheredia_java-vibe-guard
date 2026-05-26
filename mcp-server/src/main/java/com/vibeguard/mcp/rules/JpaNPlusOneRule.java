package com.vibeguard.mcp.rules;

import com.vibeguard.mcp.dto.FileContent;
import com.vibeguard.mcp.dto.Issue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects JPA N+1 query patterns in @Service and @Component beans.
 *
 * Two signal classes:
 *   1. Repository single-entity operations (findById, save, delete, exists, findBy*)
 *      called inside an explicit loop (for/while) or a stream lambda (map/forEach/filter).
 *   2. Lazy collection access (.getXxx().size/stream/forEach) inside any loop.
 *
 * Design principle: PRECISION OVER COVERAGE.
 * Ambiguous cases are not flagged. A false negative is always preferred to a false positive.
 *
 * Safe exclusions (never flagged):
 *   - @Test / @PostConstruct / main()
 *   - Batch operations: saveAll, findAllById, deleteAllInBatch, deleteAllById
 *   - Repository calls outside iteration context
 *   - @RestController (service layer is the right detection scope)
 *   - Anything inside a // comment or a string literal
 */
@Component
public class JpaNPlusOneRule implements Rule {

    // Only @Service and @Component — @RestController is intentionally excluded.
    // N+1 belongs in the service layer; flagging controllers adds noise.
    private static final Pattern SERVICE_ANNOTATION =
        Pattern.compile("@(?:\\w+\\.)*(?:Service|Component)\\b");

    private static final Pattern CLASS_DECL =
        Pattern.compile("\\bclass\\s+\\w+");

    // Fully-qualified annotation support: @org.junit.jupiter.api.Test
    private static final Pattern EXCLUDED_METHOD_ANNOTATION =
        Pattern.compile("@(?:\\w+\\.)*(?:Test|PostConstruct)\\b");

    private static final Pattern MAIN_METHOD =
        Pattern.compile("public\\s+static\\s+void\\s+main\\s*\\(");

    // Handles optional modifiers (static, final, synchronized, abstract, native)
    private static final Pattern METHOD_OPEN =
        Pattern.compile(
            "(?:public|protected|private)" +
            "(?:\\s+(?:static|final|synchronized|abstract|native))*" +
            "\\s+\\S+\\s+\\w+\\s*\\("
        );

    // Explicit loop start: for(...) or while(...)
    private static final Pattern LOOP_START =
        Pattern.compile("\\b(for|while)\\s*\\(");

    // Stream lambda on a single line: .stream().map/forEach/filter/flatMap/peek(
    private static final Pattern STREAM_LAMBDA_INLINE =
        Pattern.compile("\\.stream\\s*\\(\\s*\\)\\s*\\.\\s*(?:map|forEach|filter|flatMap|peek)\\s*\\(");

    // .stream() alone at end of line (multi-line chain pending)
    private static final Pattern STREAM_ALONE =
        Pattern.compile("\\.stream\\s*\\(\\s*\\)\\s*[,;]?\\s*$");

    // Stream chain continuation: line starts with .map/.forEach/.filter etc.
    private static final Pattern STREAM_CONTINUATION =
        Pattern.compile("^\\.\\s*(?:map|forEach|filter|flatMap|peek)\\s*\\(");

    // Dangerous single-entity repository operations.
    // Variable must contain Repository or Repo (Spring naming convention) to avoid false
    // positives from unrelated objects with similarly named methods.
    //
    // Excluded by negative lookahead:
    //   findAll(?!ById) — keeps findAllById safe (batch), flags findAll() (whole table in loop)
    //   save(?!All)     — keeps saveAll safe (batch), flags save() (single entity)
    //   delete(?!All)   — keeps deleteAll*/deleteAllInBatch safe, flags delete(entity)
    private static final Pattern REPO_SINGLE_OP = Pattern.compile(
        "\\b(\\w*[Rr]ep(?:ository|o)\\w*)\\s*\\.\\s*" +
        "(findById|findBy[A-Z]\\w*|findAll(?!ById)" +
        "|save(?!All)|saveAndFlush" +
        "|deleteById|delete(?!All)" +
        "|existsById|existsBy[A-Z]\\w*)" +
        "\\s*\\("
    );

    // Secondary guard: batch operations are always safe, never flag them even if
    // REPO_SINGLE_OP somehow matched (defense in depth).
    private static final Pattern SAFE_BATCH =
        Pattern.compile("\\.(saveAll|findAllById|deleteAllInBatch|deleteAllById|deleteAll)\\s*\\(");

    // Lazy collection access: entity.getXxx().size/stream/forEach/etc.
    // Only collection-specific terminal methods are listed to avoid matching scalar getters
    // like .getName().length() or .getDate().toEpochDay().
    private static final Pattern LAZY_COLLECTION =
        Pattern.compile(
            "\\.get[A-Z]\\w+\\s*\\(\\s*\\)\\s*\\.\\s*" +
            "(?:size|stream|iterator|forEach|isEmpty|toArray)\\s*[\\(;]"
        );

    @Override
    public String id() { return "VIBE-003"; }

    @Override
    public String description() {
        return "JPA N+1 query: single-entity repository call or lazy collection access inside iteration";
    }

    @Override
    public List<Issue> analyze(FileContent file) {
        if (!file.path().endsWith(".java")) return List.of();

        List<Issue> issues = new ArrayList<>();
        List<String> lines = file.lines();

        int braceDepth = 0;

        // Class tracking
        boolean pendingService = false;
        boolean inServiceClass = false;
        int classDepth = -1;

        // Method tracking
        boolean pendingExcluded = false;
        boolean inMethod = false;
        boolean inExcludedMethod = false;
        int methodDepth = -1;

        // Explicit loop context (for / while)
        boolean inLoop = false;
        int loopDepth = -1;

        // Stream lambda context — set when .stream().map/forEach/filter detected
        boolean inStreamLambda = false;
        int streamLambdaDepth = -1;

        // Multi-line stream chain: saw .stream() alone, next continuation line triggers lambda
        boolean pendingStream = false;

        for (int i = 0; i < lines.size(); i++) {
            String line  = lines.get(i);
            String trim  = line.strip();
            // Strip // comments AND string literal contents before pattern matching.
            // This prevents false positives from patterns appearing in comments or strings.
            String code  = codeOnly(trim);

            // --- Annotation tracking (code portion only) ---
            if (SERVICE_ANNOTATION.matcher(code).find())         pendingService  = true;
            if (EXCLUDED_METHOD_ANNOTATION.matcher(code).find()) pendingExcluded = true;

            // --- Class entry ---
            if (CLASS_DECL.matcher(code).find()) {
                if (pendingService) {
                    inServiceClass = true;
                    classDepth = braceDepth;
                }
                pendingService = false;
            }

            // --- Method entry ---
            if (inServiceClass && !inMethod && METHOD_OPEN.matcher(code).find()) {
                boolean isAbstract = !code.contains("{") && code.endsWith(";");
                if (!isAbstract) {
                    inMethod          = true;
                    inExcludedMethod  = pendingExcluded || MAIN_METHOD.matcher(code).find();
                    methodDepth       = braceDepth;
                    // Reset iteration state per-method
                    inLoop            = false;
                    loopDepth         = -1;
                    inStreamLambda    = false;
                    streamLambdaDepth = -1;
                    pendingStream     = false;
                }
                pendingExcluded = false;
            }

            // --- Iteration context detection (only in active, non-excluded methods) ---
            if (inMethod && !inExcludedMethod) {

                // Explicit loop: enter only once (nested loops are already covered)
                if (!inLoop && LOOP_START.matcher(code).find()) {
                    inLoop    = true;
                    loopDepth = braceDepth; // depth before the loop body {
                }

                // Stream lambda: single-line form .stream().map/forEach/filter(
                if (!inStreamLambda && STREAM_LAMBDA_INLINE.matcher(code).find()) {
                    inStreamLambda    = true;
                    streamLambdaDepth = braceDepth;
                }

                // Stream lambda: multi-line form — .stream() seen alone on previous line
                if (pendingStream && STREAM_CONTINUATION.matcher(code).find()) {
                    inStreamLambda    = true;
                    streamLambdaDepth = braceDepth;
                    pendingStream     = false;
                }

                // Detect .stream() alone at end of line (start of multi-line chain)
                if (STREAM_ALONE.matcher(code).find() && !STREAM_LAMBDA_INLINE.matcher(code).find()) {
                    pendingStream = true;
                } else if (!code.isEmpty() && !code.startsWith(".")) {
                    // Non-chain line without .stream() clears the pending flag
                    pendingStream = false;
                }
            }

            // --- Brace counting ---
            for (char c : line.toCharArray()) {
                if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
            }

            // --- Capture active iteration state BEFORE clearing (post-brace, pre-exit) ---
            // inLoop: we need braceDepth > loopDepth to be INSIDE the loop body (not on the for/while line itself).
            // inStreamLambda: true as long as lambda context active.
            boolean activeIteration = (inLoop && braceDepth > loopDepth) || inStreamLambda;

            // --- Exit: loop ---
            if (inLoop && braceDepth <= loopDepth && trim.contains("}")) {
                inLoop    = false;
                loopDepth = -1;
            }

            // --- Exit: stream lambda ---
            // Block lambda ({...}): exit when closing brace returns depth to pre-lambda level
            // Expression lambda (no braces): exit when statement ends with ;
            if (inStreamLambda) {
                boolean blockClosed = braceDepth < streamLambdaDepth;
                boolean stmtEnded   = trim.endsWith(";") && braceDepth == streamLambdaDepth;
                if (blockClosed || stmtEnded) {
                    inStreamLambda    = false;
                    streamLambdaDepth = -1;
                }
            }

            // --- Exit: method ---
            if (inMethod && braceDepth <= methodDepth && trim.contains("}")) {
                inMethod          = false;
                inExcludedMethod  = false;
                inLoop            = false;
                inStreamLambda    = false;
                pendingStream     = false;
                methodDepth       = -1;
            }

            // --- Exit: class ---
            if (inServiceClass && braceDepth <= classDepth && trim.contains("}")) {
                inServiceClass = false;
                classDepth     = -1;
            }

            // --- Detection: only inside active iteration, non-excluded service method ---
            if (!inServiceClass || !inMethod || inExcludedMethod || !activeIteration) {
                continue;
            }

            // Batch operations are always safe — skip the entire line
            if (SAFE_BATCH.matcher(code).find()) continue;

            // Signal 1: single-entity repository operations inside iteration
            Matcher repoMatcher = REPO_SINGLE_OP.matcher(code);
            while (repoMatcher.find()) {
                String repoVar    = repoMatcher.group(1);
                String methodName = repoMatcher.group(2);
                issues.add(new Issue(
                    id(), "CRITICAL", file.path(), i + 1,
                    "N+1 query risk: '" + repoVar + "." + methodName + "()' inside iteration — " +
                    "each cycle fires a separate SQL query; use batch loading or JOIN FETCH instead"
                ));
            }

            // Signal 2: lazy collection access inside loop
            // .getXxx().size/stream/forEach triggers a SELECT per entity when the collection is LAZY
            if (LAZY_COLLECTION.matcher(code).find()) {
                issues.add(new Issue(
                    id(), "CRITICAL", file.path(), i + 1,
                    "N+1 query risk: lazy collection access inside iteration — " +
                    "triggers a SELECT per entity; use @EntityGraph or JOIN FETCH to eagerly load"
                ));
            }
        }

        return issues;
    }

    /**
     * Returns the code portion of a line:
     *   1. strips everything from // onward (line comment)
     *   2. replaces "..." string content with "" to prevent matching patterns inside literals
     *
     * Known limitation: does not handle escaped quotes inside strings (e.g. "say \"hi\"").
     * This is an acceptable trade-off for a regex-based scanner.
     */
    private static String codeOnly(String trimmed) {
        int commentIdx = trimmed.indexOf("//");
        String noComment = commentIdx >= 0 ? trimmed.substring(0, commentIdx) : trimmed;
        // Strip string literal contents: "..." → ""
        return noComment.replaceAll("\"[^\"]*\"", "\"\"");
    }
}
