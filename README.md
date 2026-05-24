# java-vibe-guard — Anti-Vibe-Coding Defense Stack

A multi-layer defense system against vibe coding anti-patterns in Java/Spring Boot projects. Combines static analysis, MCP-native tooling, and cognitive governance to catch production bugs that compile cleanly and fail under load.

## Structure

```
java-vibe-guard/
├── cli/          # Node.js static analyzer (npm package)
└── mcp-server/   # Spring Boot MCP server for Claude Code integration
```

---

## The Problem

Vibe coding produces code that compiles, passes mocked unit tests, and fails in production under real load. LLMs generate syntactically plausible patterns that violate architectural invariants only visible at runtime — blocking calls inside transactions, direct cross-layer access, missing observability. No single tool catches all of them because they operate at different points in the development cycle.

---

## The Three Layers

### Layer 1 — senior-architect (CLAUDE.md global gate)

**When it acts:** before Claude writes a single line of code.

**Mechanism:** mandatory rule in `~/.claude/CLAUDE.md` that forces Claude to internally run the `ai-risk-constraints.md` protocol before any relevant change. The protocol runs five checks (blast radius, reversibility, dependencies, architecture, security) and emits an explicit decision: `APPROVE / REJECT / APPROVE WITH RISKS`. If `REJECT`, Claude explains what fails and what is needed to approve it. If the gate doesn't pass, the code isn't written.

**What it eliminates:** incorrect architectural decisions that no linter can see. Examples: introducing persistence where it shouldn't exist, adding a dependency that creates unnecessary coupling, generating code with shared state between requests in a stateless context, changing business logic without understanding the existing contract. It's the only point in the stack that can stop a change before it exists.

**Limitation:** operates on declared intent, not written code. Cannot detect implementation errors arising from the gap between what the LLM said it would do and what it actually generated.

---

### Layer 2 — java-vibe-guard CLI (`cli/`)

**When it acts:** after code exists, before or after commit, integrable into CI.

**Mechanism:** Node.js tool that recursively scans a Java/Spring Boot project applying static analysis rules based on regex patterns and context heuristics. Current rules detect: blocking calls in async annotations (`@Async`, `@KafkaListener`), direct Controller→Repository or Controller→Kafka access (layer violation), endpoints without structured logging. Each finding includes file, line, severity, and explanation of why it's a production anti-pattern.

**What it eliminates:** the gap between what the LLM declares and what it generates. An LLM can pass the architecture gate saying "I'll handle this asynchronously" and then generate a blocking `.get()` in the same method. The CLI detects it independently of intent. Also works as an audit tool on pre-existing or legacy code.

**Limitation:** textual analysis, not full AST. Doesn't understand real control flow or object types. Can produce false positives with coincidental naming, and cannot reason about anti-patterns that only emerge from the composition of multiple methods.

```bash
cd cli
npx java-vibe-guard ./path/to/project
npx java-vibe-guard ./path/to/project --ignore target,build
```

---

### Layer 3 — java-vibe-guard MCP Server (`mcp-server/`)

**When it acts:** during active Claude Code workflow, as a tool available in context.

**Mechanism:** MCP (Model Context Protocol) server implemented in Spring Boot that exposes `analyzeProject` as a native Claude Code tool. When Claude generates code for a project, it can invoke the analysis directly as part of its reasoning — before declaring the task complete, after a refactor, or when the user requests it. The result (files analyzed, issues by severity, exact line and diagnostic message) enters Claude's context and can inform the next action.

**What it adds over the CLI:** closes the feedback loop within the same session. Instead of manually running the CLI and pasting the output, Claude can analyze, see the results, fix, and re-analyze in a single conversation. Also serves as a validation signal Claude can use to confirm that a change it just made didn't introduce new anti-patterns.

**Limitation:** same as the CLI, inheriting its static analysis constraints. Also depends on Claude deciding to invoke the tool — it is not an automatic barrier like the senior-architect gate.

```bash
# Build
cd mcp-server
mvn package -DskipTests

# Register in Claude Code (user scope — available in all projects)
claude mcp add java-vibe-guard -s user -- java -jar mcp-server/target/java-vibe-guard-mcp-1.0.0-SNAPSHOT.jar
```

---

## How They Act as a Pipeline

```
[User request]
       │
       ▼
┌─────────────────────────────┐
│   LAYER 1: senior-architect │  ← "Should this be done?"
│   Pre-code cognitive gate   │     APPROVE / REJECT
└──────────────┬──────────────┘
               │ APPROVE
               ▼
┌─────────────────────────────┐
│   Claude generates code     │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│   LAYER 3: MCP server       │  ← "Is the generated code safe?"
│   In-session feedback loop  │     Analyze → fix → re-analyze
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│   LAYER 2: CLI in CI        │  ← "Is the full repo clean?"
│   Pre-merge gate            │     Blocks on unresolved findings
└─────────────────────────────┘
```

The three layers are deliberately redundant in purpose but not in mechanism. The redundancy is correct because each layer has a different failure vector: the cognitive gate can approve incorrect code, the MCP may not be invoked, the CLI may have false negatives. None fail simultaneously against the same type of error.

---

## What They Eliminate Together

| Anti-pattern | Layer that catches it |
|---|---|
| Incorrect architectural decision ("add persistence here") | Layer 1 |
| `.get()` blocking inside `@Transactional` or `@KafkaListener` | Layers 2 + 3 |
| Controller accessing repository directly | Layers 2 + 3 |
| Endpoint without structured logging | Layers 2 + 3 |
| Generated code the LLM declares correct but isn't | Layer 3 (immediate feedback) |
| Regression introduced by refactoring | Layer 3 (post-change re-analysis) |
| Technical debt in untouched legacy code | Layer 2 (CI audit) |

---

## Stack Evaluation

What makes it solid is not the sophistication of any individual layer, but that the pipeline operates at zero marginal cost per analysis: the cognitive gate is part of Claude's process, the MCP is a tool call within the session, the CLI is executable in any CI without licenses. The total system cost is zero. The only real cost is not having it: production code with anti-patterns that only fail under load.
