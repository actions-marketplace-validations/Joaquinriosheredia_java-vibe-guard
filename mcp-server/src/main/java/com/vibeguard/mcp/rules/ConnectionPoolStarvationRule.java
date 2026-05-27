package com.vibeguard.mcp.rules;

import com.vibeguard.mcp.dto.FileContent;
import com.vibeguard.mcp.dto.Issue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Detects patterns that cause connection pool starvation in Spring Boot applications.
 *
 * A DB connection acquired by @Transactional is held for the entire method duration.
 * Any blocking external call inside that boundary pins the connection, starving the
 * pool under concurrent load (e.g. 10-connection HikariCP default with 200 req/s).
 *
 * Gate: detection only activates when @Transactional is EXPLICITLY present on the
 * method or the enclosing class. Propagation through intermediary services is NOT
 * inferred — if it isn't visible in this file, it isn't flagged.
 *
 * Signal classes:
 *   CRITICAL — @Transactional method contains a blocking external call:
 *              Thread.sleep, Future.get, RestTemplate.*, WebClient.block(),
 *              Files.read*, InputStream.read, direct JDBC Statement.execute*
 *   MAJOR    — @Transactional present on a @RestController method:
 *              the connection stays open during HTTP response serialization
 *
 * Explicit exclusions:
 *   - @Test / @PostConstruct / main() → never flagged
 *   - @Transactional(readOnly=true) without blocking ops → not flagged
 *   - Repository calls inside @Transactional without external I/O → not flagged
 *   - Blocking calls outside @Transactional context → not flagged
 *   - Patterns in // comments or string literals → stripped by codeOnly()
 */
@Component
public class ConnectionPoolStarvationRule implements Rule {

    // Class-level annotations
    private static final Pattern REST_CONTROLLER_ANNOTATION =
        Pattern.compile("@(?:\\w+\\.)*RestController\\b");

    private static final Pattern TRANSACTIONAL_ANNOTATION =
        Pattern.compile("@(?:\\w+\\.)*Transactional\\b");

    // readOnly=true on the same line as @Transactional (single-line annotation form).
    // Multi-line form handled by continuation check below.
    private static final Pattern TRANSACTIONAL_READONLY =
        Pattern.compile("@(?:\\w+\\.)*Transactional\\s*\\([^)]*readOnly\\s*=\\s*true");

    private static final Pattern CLASS_DECL =
        Pattern.compile("\\bclass\\s+\\w+");

    // Method boundary
    private static final Pattern EXCLUDED_METHOD_ANNOTATION =
        Pattern.compile("@(?:\\w+\\.)*(?:Test|PostConstruct)\\b");

    private static final Pattern MAIN_METHOD =
        Pattern.compile("public\\s+static\\s+void\\s+main\\s*\\(");

    private static final Pattern METHOD_OPEN = Pattern.compile(
        "(?:public|protected|private)" +
        "(?:\\s+(?:static|final|synchronized|abstract|native))*" +
        "\\s+\\S+\\s+\\w+\\s*\\("
    );

    // Blocking external calls that hold the DB connection for their full duration.
    //
    // RestTemplate: variable name must contain RestTemplate/restTemplate as a substring
    // to avoid false positives from unrelated objects with similarly named methods.
    //
    // Future.get: variable name must contain Future/future to target the blocking wait.
    // .block(): WebClient reactive termination operator — distinctive enough to match broadly.
    // Files.*: standard library read methods that involve file I/O.
    // JDBC direct: only named conventional variables (statement/pstmt/stmt/preparedStatement).
    private static final Pattern BLOCKING_CALL = Pattern.compile(
        "\\bThread\\.sleep\\s*\\(" +
        "|\\b\\w*[Ff]uture\\w*\\s*\\.\\s*get\\s*\\(" +
        "|\\b\\w*[Rr]est[Tt]emplate\\w*\\s*\\.\\s*\\w+\\s*\\(" +
        "|\\.\\s*block\\s*\\(" +
        "|\\bFiles\\.(?:readAllBytes|readAllLines|readString|lines)\\s*\\(" +
        "|\\binputStream\\s*\\.\\s*read\\w*\\s*\\(" +
        "|\\b(?:statement|pstmt|stmt|preparedStatement)\\s*\\.\\s*execute\\w*\\s*\\("
    );

    @Override
    public String id() { return "VIBE-005"; }

    @Override
    public String description() {
        return "Connection pool starvation: @Transactional method holds DB connection during external I/O";
    }

    @Override
    public List<Issue> analyze(FileContent file) {
        if (!file.path().endsWith(".java")) return List.of();

        List<Issue> issues = new ArrayList<>();
        List<String> lines = file.lines();

        int braceDepth = 0;

        // Class-level state
        boolean pendingRestController = false;
        boolean pendingClassTx        = false;
        boolean pendingClassReadOnly  = false;
        boolean inClass               = false;
        boolean isRestControllerClass = false;
        boolean hasClassTx            = false;
        boolean classReadOnly         = false;
        int     classDepth            = -1;

        // Method-level state
        boolean pendingExcluded       = false;
        boolean pendingMethodTx       = false;
        boolean pendingMethodReadOnly = false;
        boolean inMethod              = false;
        boolean inExcludedMethod      = false;
        boolean methodHasTx           = false;
        boolean methodReadOnly        = false;
        boolean emittedMajor          = false;
        int     methodDepth           = -1;
        int     methodLine            = -1;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trim = line.strip();
            String code = codeOnly(trim);

            // --- Pre-class annotation tracking ---
            if (!inClass) {
                if (REST_CONTROLLER_ANNOTATION.matcher(code).find()) pendingRestController = true;
                if (TRANSACTIONAL_ANNOTATION.matcher(code).find()) {
                    pendingClassTx       = true;
                    pendingClassReadOnly = TRANSACTIONAL_READONLY.matcher(code).find();
                }
                // Multi-line @Transactional( \n readOnly = true \n ): continuation line
                if (pendingClassTx && !pendingClassReadOnly
                        && code.contains("readOnly") && code.contains("true")) {
                    pendingClassReadOnly = true;
                }
            }

            // --- Class entry ---
            if (!inClass && CLASS_DECL.matcher(code).find()) {
                inClass               = true;
                isRestControllerClass = pendingRestController;
                hasClassTx            = pendingClassTx;
                classReadOnly         = pendingClassReadOnly;
                classDepth            = braceDepth;
                pendingRestController = false;
                pendingClassTx        = false;
                pendingClassReadOnly  = false;
            }

            // --- Method-level annotation tracking (inside class, outside method) ---
            if (inClass && !inMethod) {
                if (EXCLUDED_METHOD_ANNOTATION.matcher(code).find()) pendingExcluded = true;
                if (TRANSACTIONAL_ANNOTATION.matcher(code).find()) {
                    pendingMethodTx       = true;
                    pendingMethodReadOnly = TRANSACTIONAL_READONLY.matcher(code).find();
                }
                // Multi-line readOnly continuation
                if (pendingMethodTx && !pendingMethodReadOnly
                        && code.contains("readOnly") && code.contains("true")) {
                    pendingMethodReadOnly = true;
                }
            }

            // --- Method entry ---
            if (inClass && !inMethod && METHOD_OPEN.matcher(code).find()) {
                boolean isAbstract = !code.contains("{") && code.endsWith(";");
                if (!isAbstract) {
                    inMethod         = true;
                    inExcludedMethod = pendingExcluded || MAIN_METHOD.matcher(code).find();
                    // Class-level @Tx propagates; method-level overrides readOnly calculation
                    methodHasTx   = pendingMethodTx || hasClassTx;
                    methodReadOnly = pendingMethodTx ? pendingMethodReadOnly : classReadOnly;
                    emittedMajor  = false;
                    methodDepth   = braceDepth;
                    methodLine    = i + 1;
                }
                pendingExcluded       = false;
                pendingMethodTx       = false;
                pendingMethodReadOnly = false;
            }

            // --- Brace counting ---
            for (char c : line.toCharArray()) {
                if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
            }

            // --- Exit: method ---
            if (inMethod && braceDepth <= methodDepth && trim.contains("}")) {
                inMethod         = false;
                inExcludedMethod = false;
                methodHasTx      = false;
                methodReadOnly   = false;
                emittedMajor     = false;
                methodDepth      = -1;
                methodLine       = -1;
            }

            // --- Exit: class ---
            if (inClass && braceDepth <= classDepth && trim.contains("}")) {
                inClass               = false;
                isRestControllerClass = false;
                hasClassTx            = false;
                classReadOnly         = false;
                classDepth            = -1;
            }

            // Only scan inside active, non-excluded, transactional methods
            if (!inMethod || inExcludedMethod || !methodHasTx) continue;

            // --- MAJOR: @Transactional in @RestController ---
            // Emit once per method on the method declaration line.
            // readOnly methods without I/O are excluded (connection impact is minimal
            // and the spec explicitly carves them out).
            if (isRestControllerClass && !emittedMajor && !methodReadOnly) {
                issues.add(new Issue(id(), "MAJOR", file.path(), methodLine,
                    "Connection pool starvation: @Transactional in @RestController holds DB connection " +
                    "open during HTTP response serialization — move transaction to a @Service layer method"
                ));
                emittedMajor = true;
            }

            // --- CRITICAL: blocking external call inside @Transactional ---
            if (BLOCKING_CALL.matcher(code).find()) {
                issues.add(new Issue(id(), "CRITICAL", file.path(), i + 1,
                    "Connection pool starvation: blocking call inside @Transactional holds DB connection " +
                    "open — move external I/O outside the transaction boundary or use non-blocking patterns"
                ));
            }
        }

        return issues;
    }

    /**
     * Strips // line comments and string literal contents before pattern matching.
     * Prevents false positives from patterns appearing in comments or string values.
     */
    private static String codeOnly(String trimmed) {
        int commentIdx = trimmed.indexOf("//");
        String noComment = commentIdx >= 0 ? trimmed.substring(0, commentIdx) : trimmed;
        return noComment.replaceAll("\"[^\"]*\"", "\"\"");
    }
}
