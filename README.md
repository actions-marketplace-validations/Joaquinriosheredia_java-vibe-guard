# java-vibe-guard — Anti-Vibe-Coding Defense Stack

[![GitHub Action](https://img.shields.io/badge/GitHub_Action-available-blue?logo=github-actions)](https://github.com/Joaquinriosheredia/java-vibe-guard/actions)

![java-vibe-guard demo](https://raw.githubusercontent.com/Joaquinriosheredia/java-vibe-guard-demo/master/demo.gif)

A multi-layer defense system against vibe coding anti-patterns in Java/Spring Boot projects. Combines static analysis, MCP-native tooling, and cognitive governance to catch production bugs that compile cleanly and fail under load.

## Quick Start

### CLI
```bash
npx java-vibe-guard ./your-spring-project
```

### GitHub Actions (CI)
```yaml
- uses: Joaquinriosheredia/java-vibe-guard@v1
  with:
    path: '.'
    fail-on: 'critical'
```

Fails the build on CRITICAL findings. Zero configuration required.

### MCP Server (Claude Code)
1. Download java-vibe-guard-mcp-0.1.0.jar from releases
2. `claude mcp add java-vibe-guard -s user -- java -jar /path/to/java-vibe-guard-mcp-0.1.0.jar`

Requires Java 21+

## Structure

```
java-vibe-guard/
├── cli/          # Node.js static analyzer (npm package)
└── mcp-server/   # Spring Boot MCP server for Claude Code integration
```

---

## Active Rules — MCP Server (VIBE-001 to VIBE-007)

**7 rules · 102 tests · 0 false positives**

| Code | Rule | Description |
|------|------|-------------|
| VIBE-001 | `TransactionalAsyncRule` | `.get()` / `.block()` inside `@Transactional` exhausts the DB connection pool |
| VIBE-002 | `ReactorBlockingCallRule` | `.block()` / `.blockFirst()` / `.toFuture().get()` in reactive `@RestController` or `@Service` |
| VIBE-003 | `JpaNPlusOneRule` | Single-entity repository call (`findById`, `save`, `delete`) inside a loop or stream lambda |
| VIBE-004 | `VirtualThreadsMisuseRule` | `synchronized` or `ThreadLocal` in Virtual Threads context pins the carrier platform thread |
| VIBE-005 | `ConnectionPoolStarvationRule` | Blocking external call (HTTP, sleep, file I/O, `Future.get()`) inside `@Transactional` |
| VIBE-006 | `KafkaRebalanceHazardRule` | `@KafkaListener` without explicit `groupId`, or blocking call inside listener thread |
| VIBE-007 | `MdcContextLeakRule` | `MDC.put()` in `@Async` / `@Scheduled` without `MDC.clear()` leaks context across thread reuse |

All rules share the same design principles: explicit annotation gate (no inference), `codeOnly()` stripping to prevent matches in comments and string literals, and precision over coverage — when in doubt, the rule does not flag.

---

## The Problem

Vibe coding produces code that compiles, passes mocked unit tests, and fails in production under real load. LLMs generate syntactically plausible patterns that violate architectural invariants only visible at runtime — blocking calls inside transactions, direct cross-layer access, missing observability. No single tool catches all of them because they operate at different points in the development cycle.

---

## Two Layers

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

#### GitHub Action

Add to any Java project's workflow — no installation required:

```yaml
- name: java-vibe-guard
  uses: Joaquinriosheredia/java-vibe-guard@v1
  with:
    path: '.'            # directory to scan (default: .)
    fail-on: 'critical'  # fail step on CRITICAL findings (default)
```

Full options:

```yaml
- uses: Joaquinriosheredia/java-vibe-guard@v1
  with:
    path: '.'
    rule: ''             # blank = all rules; or: blocking | layers | kafka | transactions | observability
    ignore: 'labs,demo'  # comma-separated dirs to skip
    fail-on: 'critical'  # critical | never
    upload-report: 'true'  # attach JSON report as artifact

  # Outputs available in subsequent steps:
  #   ${{ steps.guard.outputs.critical }}
  #   ${{ steps.guard.outputs.major }}
  #   ${{ steps.guard.outputs.warning }}
  #   ${{ steps.guard.outputs.healthy }}
  #   ${{ steps.guard.outputs.report-json }}
```

The action writes a markdown table to the GitHub Actions summary panel and uploads the full JSON report as a workflow artifact (30-day retention).

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

The two layers are deliberately redundant in purpose but not in mechanism. The redundancy is correct because each layer has a different failure vector: the MCP may not be invoked, the CLI may have false negatives. None fail simultaneously against the same type of error.

---

## What They Eliminate Together

| Anti-pattern | Layer that catches it |
|---|---|
| `.get()` blocking inside `@Transactional` or `@KafkaListener` | Layers 2 + 3 |
| Controller accessing repository directly | Layers 2 + 3 |
| Endpoint without structured logging | Layers 2 + 3 |
| Generated code the LLM declares correct but isn't | Layer 3 (immediate feedback) |
| Regression introduced by refactoring | Layer 3 (post-change re-analysis) |
| Technical debt in untouched legacy code | Layer 2 (CI audit) |


---

## Stack Evaluation

What makes it solid is not the sophistication of any individual layer, but that the pipeline operates at zero marginal cost per analysis: the cognitive gate is part of Claude's process, the MCP is a tool call within the session, the CLI is executable in any CI without licenses. The total system cost is zero. The only real cost is not having it: production code with anti-patterns that only fail under load.

---

## Validation

- **102 tests**, 0 false positives.
- Validated on **17,137 real files** across 10 Spring Boot repositories.
- **0 confirmed false positives** in CRITICAL rules.

## Found in the Wild

Finding 1 — VIBE-005: @Transactional on @RestController holds DB connections during HTTP serialization
  
Repo: eugenp/tutorials (~35k stars), JHipster 8 monolithic module
Rule: VIBE-005 · ConnectionPoolStarvation · MAJOR
File: jhipster-8-modules/jhipster-8-monolithic/.../web/rest/AuthorityResource.java
  
@RestController
@RequestMapping("/api/authorities")
@Transactional // ← flagged
public class AuthorityResource {
  
@GetMapping("")
public List<Authority> getAllAuthorities() {
return authorityRepository.findAll();
}
  
@DeleteMapping("/{id}")
public ResponseEntity<Void> deleteAuthority(@PathVariable String id) {
authorityRepository.deleteById(id);
return ResponseEntity.noContent()...build();
}
}
  
Why it fails under load:
@Transactional on the class means every endpoint opens a DB connection on the first repository call
and holds it open until the method fully returns — which includes JSON serialization of the response
body, header writing, and Tomcat's response commit. DB serialization is measured in microseconds;
HTTP response finalization under load can take milliseconds of extra latency as the response buffer
flushes. At 500 concurrent requests against a pool capped at 20 connections, the 480 threads queuing
for a connection accumulate serialization latency and keep connections checked out longer than
necessary, accelerating pool exhaustion.
  
Estimated impact:
~10–30% connection hold-time overhead per request; at sustained load, connection pool exhaustion
causes HikariCP - Connection is not available, request timed out after 30000ms errors. JHipster
scaffolding generates this pattern for every admin resource controller, so the issue is typically
replicated across all CRUD controllers in a generated codebase.
  
---
Finding 2 — VIBE-002: .block() called on Schedulers.parallel() thread
  
Repo: eugenp/tutorials (~35k stars), spring-reactive-modules
Rule: VIBE-002 · ReactorBlockingCall · CRITICAL
File: spring-reactive-modules/spring-reactive-4/.../service/FileContentSearchService.java
  
public Mono<Boolean> blockingSearchOnParallelThreadPool(String fileName, String searchTerm) {
return Mono.just("")
.publishOn(Schedulers.parallel()) // switches to parallel scheduler
.map(s -> fileService.getFileContentAsString(fileName)
.block() // ← flagged: blocks a parallel thread
.contains(searchTerm));
}
  
Why it fails under load:
Schedulers.parallel() is a fixed-size thread pool sized to
Runtime.getRuntime().availableProcessors() — typically 4 to 8 threads on production hardware. Its
intended use is CPU-bound computation, not I/O. Calling .block() inside a map operator pinned to
this scheduler occupies one of those threads for the entire duration of the file read. With 5
concurrent requests on a 4-core machine, all parallel threads are blocked waiting for file I/O; no
further reactive operators — including other pending HTTP responses, ongoing DB queries, or
WebClient calls — can be scheduled. The effect is functionally equivalent to a deadlock from the
reactive pipeline's perspective.
  
Estimated impact:
Under concurrent load, complete stall of all reactive work scheduled on Schedulers.parallel(). On a
4-core server, 4 simultaneous requests to this endpoint render the service unresponsive to all
reactive traffic until the blocked threads are released.
  
---
Finding 3 — VIBE-006: Thread.sleep() inside @KafkaListener delays acknowledgement and triggers rebalances
  
Repo: eugenp/tutorials (~35k stars), spring-kafka-2 monitoring module
Rule: VIBE-006 · KafkaRebalanceHazard · CRITICAL
File: spring-kafka-2/.../monitoring/simulation/ConsumerSimulator.java
  
@KafkaListener(
topics = "${monitor.topic.name}",
containerFactory = "kafkaListenerContainerFactory",
autoStartup = "${monitor.consumer.simulate}"
)
public void listenGroup(String message) throws InterruptedException {
Thread.sleep(10L); // ← flagged: stalls listener thread per message
}
  
Why it fails under load:
The Kafka consumer listener thread is single-threaded per partition by default. Thread.sleep(10L)
introduces a mandatory 10 ms delay per message. At 1,000 messages/second per partition, the listener
can process at most 100 messages/second — a 10× throughput reduction. As consumer lag grows,
downstream systems relying on low-latency event processing experience data staleness. More
critically: if max.poll.interval.ms (default 5 minutes) is exceeded — which occurs if a message
volume spike causes the poll loop to be blocked beyond the interval — Kafka considers the consumer
dead, triggers a group rebalance, and temporarily unassigns all partitions from the consumer. During
rebalance, processing halts for all partitions in the group. Any messages fetched before rebalance
but not yet committed are redelivered after rebalance, causing duplicate processing unless the
consumer is idempotent.
  
Estimated impact:
Throughput capped at 1000ms / sleep_duration messages/second per partition. At Thread.sleep(10L):
100 msg/s max. If multiple partitions share the same listener container, each partition waits for
the previous message to complete — multiplying the lag. In monitoring contexts where this pattern
appeared, consumer lag alarms are typically the first production signal.

