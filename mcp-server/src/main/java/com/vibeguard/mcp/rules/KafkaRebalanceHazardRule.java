package com.vibeguard.mcp.rules;

import com.vibeguard.mcp.dto.FileContent;
import com.vibeguard.mcp.dto.Issue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Detects patterns that cause consumer lag, unnecessary rebalances, and
 * throughput degradation in Spring Kafka listeners.
 *
 * Three signal classes:
 *   WARNING  — @KafkaListener without groupId in annotation:
 *              may be configured via application.yml or env vars; if not,
 *              each restart joins a random consumer group causing rebalances.
 *   CRITICAL — @KafkaListener with groupId explicitly set to "":
 *              empty string is never a valid consumer group, guaranteed rebalance.
 *   CRITICAL — Blocking call inside @KafkaListener method body:
 *              the listener thread stalls, max.poll.interval.ms can expire,
 *              Kafka considers the consumer dead and triggers a rebalance.
 *
 * Gate: detection only activates when @KafkaListener is explicitly present on
 * the method. Class-level or inferred listeners are not covered.
 *
 * Explicit exclusions:
 *   - @Test / @PostConstruct / main() → never flagged
 *   - groupId with a non-empty string value → missing-groupId CRITICAL suppressed
 *   - Blocking calls outside @KafkaListener methods → not flagged
 *   - Patterns in // comments or string literals → stripped by codeOnly()
 *
 * groupId detection handles both single-line and multi-line @KafkaListener
 * annotations by accumulating lines until the annotation's parentheses close.
 */
@Component
public class KafkaRebalanceHazardRule implements Rule {

    private static final Pattern KAFKA_LISTENER =
        Pattern.compile("@(?:\\w+\\.)*KafkaListener\\b");

    private static final Pattern EXCLUDED_METHOD_ANNOTATION =
        Pattern.compile("@(?:\\w+\\.)*(?:Test|PostConstruct)\\b");

    private static final Pattern MAIN_METHOD =
        Pattern.compile("public\\s+static\\s+void\\s+main\\s*\\(");

    private static final Pattern METHOD_OPEN = Pattern.compile(
        "(?:public|protected|private)" +
        "(?:\\s+(?:static|final|synchronized|abstract|native))*" +
        "\\s+\\S+\\s+\\w+\\s*\\("
    );

    // groupId must be present and contain at least one character (non-empty string).
    // Applied to the raw annotation text (string values preserved, comments stripped).
    private static final Pattern GROUP_ID_VALID =
        Pattern.compile("groupId\\s*=\\s*\"[^\"]+\"");

    // groupId explicitly set to empty string "" — always CRITICAL (invalid group name).
    private static final Pattern GROUP_ID_EXPLICIT_EMPTY =
        Pattern.compile("groupId\\s*=\\s*\"\"");

    // Blocking calls that stall the listener thread beyond max.poll.interval.ms.
    // Same detection surface as ConnectionPoolStarvationRule — any call that blocks
    // the calling thread for an unpredictable duration is a rebalance risk here.
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
    public String id() { return "VIBE-006"; }

    @Override
    public String description() {
        return "Kafka rebalance hazard: missing groupId or blocking call inside @KafkaListener";
    }

    @Override
    public List<Issue> analyze(FileContent file) {
        if (!file.path().endsWith(".java")) return List.of();

        List<Issue> issues = new ArrayList<>();
        List<String> lines = file.lines();

        int braceDepth = 0;

        // Annotation accumulation state (outside methods)
        boolean       pendingExcluded           = false;
        boolean       pendingKafkaListener      = false;
        boolean       pendingGroupIdValid        = false;
        boolean       pendingGroupIdExplicitEmpty = false;
        int           kafkaParenDepth            = 0;
        StringBuilder kafkaAnnotBuf              = null;

        // Method state
        boolean inMethod                    = false;
        boolean inExcludedMethod            = false;
        boolean inKafkaListenerMethod       = false;
        boolean listenerGroupIdValid        = false;
        boolean listenerGroupIdExplicitEmpty = false;
        boolean emittedGroupIdIssue         = false;
        int     methodDepth                 = -1;
        int     methodLine                  = -1;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trim = line.strip();
            // raw: comments stripped but string values kept — used for groupId detection
            String raw  = noComment(trim);
            // code: comments + string contents stripped — used for pattern detection
            String code = codeOnly(trim);

            // --- Annotation tracking (outside method bodies) ---
            if (!inMethod) {
                if (EXCLUDED_METHOD_ANNOTATION.matcher(code).find()) pendingExcluded = true;

                if (KAFKA_LISTENER.matcher(code).find()) {
                    // Start accumulating @KafkaListener annotation text
                    pendingKafkaListener = true;
                    kafkaAnnotBuf        = new StringBuilder(raw);
                    kafkaParenDepth      = 0;
                    for (char c : raw.toCharArray()) {
                        if (c == '(')                        kafkaParenDepth++;
                        else if (c == ')' && kafkaParenDepth > 0) kafkaParenDepth--;
                    }
                    if (kafkaParenDepth == 0) {
                        // Single-line or no-paren annotation — accumulation complete
                        pendingGroupIdValid         = GROUP_ID_VALID.matcher(kafkaAnnotBuf).find();
                        pendingGroupIdExplicitEmpty = !pendingGroupIdValid
                            && GROUP_ID_EXPLICIT_EMPTY.matcher(kafkaAnnotBuf).find();
                    }
                } else if (pendingKafkaListener && kafkaParenDepth > 0) {
                    // Continuation line of a multi-line @KafkaListener(...) annotation
                    kafkaAnnotBuf.append(' ').append(raw);
                    for (char c : raw.toCharArray()) {
                        if (c == '(')                        kafkaParenDepth++;
                        else if (c == ')' && kafkaParenDepth > 0) kafkaParenDepth--;
                    }
                    if (kafkaParenDepth == 0) {
                        pendingGroupIdValid         = GROUP_ID_VALID.matcher(kafkaAnnotBuf).find();
                        pendingGroupIdExplicitEmpty = !pendingGroupIdValid
                            && GROUP_ID_EXPLICIT_EMPTY.matcher(kafkaAnnotBuf).find();
                    }
                }
            }

            // --- Method entry ---
            if (!inMethod && METHOD_OPEN.matcher(code).find()) {
                boolean isAbstract = !code.contains("{") && code.endsWith(";");
                if (!isAbstract) {
                    inMethod                     = true;
                    inExcludedMethod             = pendingExcluded || MAIN_METHOD.matcher(code).find();
                    inKafkaListenerMethod        = pendingKafkaListener;
                    listenerGroupIdValid         = pendingGroupIdValid;
                    listenerGroupIdExplicitEmpty = pendingGroupIdExplicitEmpty;
                    emittedGroupIdIssue          = false;
                    methodDepth                  = braceDepth;
                    methodLine                   = i + 1;
                }
                pendingExcluded           = false;
                pendingKafkaListener      = false;
                pendingGroupIdValid        = false;
                pendingGroupIdExplicitEmpty = false;
                kafkaAnnotBuf             = null;
                kafkaParenDepth           = 0;
            }

            // --- Brace counting ---
            for (char c : line.toCharArray()) {
                if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
            }

            // --- Exit: method ---
            if (inMethod && braceDepth <= methodDepth && trim.contains("}")) {
                inMethod                     = false;
                inExcludedMethod             = false;
                inKafkaListenerMethod        = false;
                listenerGroupIdValid         = false;
                listenerGroupIdExplicitEmpty = false;
                emittedGroupIdIssue          = false;
                methodDepth                  = -1;
                methodLine                   = -1;
            }

            // Only scan inside active, non-excluded @KafkaListener methods
            if (!inMethod || inExcludedMethod || !inKafkaListenerMethod) continue;

            // --- CRITICAL/WARNING: missing or empty groupId ---
            // Emitted once per method on the method declaration line so the developer
            // sees the annotation that needs to be fixed, not a line inside the body.
            if (!listenerGroupIdValid && !emittedGroupIdIssue) {
                if (listenerGroupIdExplicitEmpty) {
                    issues.add(new Issue(id(), "CRITICAL", file.path(), methodLine,
                        "Kafka rebalance hazard: @KafkaListener without explicit groupId — " +
                        "each restart joins a new random consumer group, causing rebalances " +
                        "and potential duplicate or lost message processing; " +
                        "add groupId = \"your-consumer-group\""
                    ));
                } else {
                    issues.add(new Issue(id(), "WARNING", file.path(), methodLine,
                        "groupId not found in @KafkaListener annotation. " +
                        "Verify it is configured via application.yml, " +
                        "application-*.yml or environment variables. " +
                        "If not configured elsewhere, consumer reprocessing " +
                        "may occur after restarts."
                    ));
                }
                emittedGroupIdIssue = true;
            }

            // --- CRITICAL: blocking call inside listener ---
            if (BLOCKING_CALL.matcher(code).find()) {
                issues.add(new Issue(id(), "CRITICAL", file.path(), i + 1,
                    "Kafka rebalance hazard: blocking call inside @KafkaListener delays " +
                    "acknowledgement and can exceed max.poll.interval.ms, triggering a " +
                    "rebalance — offload to an async executor or use non-blocking I/O"
                ));
            }
        }

        return issues;
    }

    /** Strips // line comments; preserves string literal content (needed for groupId check). */
    private static String noComment(String trimmed) {
        int idx = trimmed.indexOf("//");
        return idx >= 0 ? trimmed.substring(0, idx) : trimmed;
    }

    /** Strips // line comments AND string literal contents (for blocking-call pattern matching). */
    private static String codeOnly(String trimmed) {
        int commentIdx = trimmed.indexOf("//");
        String noComment = commentIdx >= 0 ? trimmed.substring(0, commentIdx) : trimmed;
        return noComment.replaceAll("\"[^\"]*\"", "\"\"");
    }
}
