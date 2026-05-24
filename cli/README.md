# java-vibe-guard

> Stop shipping AI-generated Java code that looks right but fails in production. Get caught in seconds.

[![npm version](https://img.shields.io/npm/v/java-vibe-guard?color=blue)](https://www.npmjs.com/package/java-vibe-guard)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Node.js ≥18](https://img.shields.io/badge/node-%3E%3D18-brightgreen)](https://nodejs.org)

---

## The problem

You (or your AI assistant) wrote Spring Boot code that compiles, the tests pass, and it looks clean. Then it goes to production and:

- A `@Scheduled` job blocks all threads with `.get()` on a CompletableFuture
- A Controller calls a Repository directly, skipping the Service layer
- A Kafka consumer has no retry strategy — one bad message kills the consumer
- `@Transactional` + `@Async` silently loses the transaction
- Every endpoint is a black box with zero structured logging

**java-vibe-guard** scans your project in seconds and tells you exactly what is wrong and how to fix it.

---

## Quick start

```bash
# No install needed
npx java-vibe-guard ./my-spring-project

# Or install globally
npm install -g java-vibe-guard
java-vibe-guard ./my-spring-project
```

---

## What it detects

| Rule | Severity | Pattern |
|------|----------|---------|
| **blocking** | 🔴 CRITICAL | `.get()` / `.join()` / `.block()` / `Thread.sleep()` inside `@Scheduled`, `@KafkaListener`, `@Async` |
| **layers** | 🟡 MAJOR | `@Controller` accessing `*Repository` or `KafkaTemplate` directly |
| **transactions** | 🟡 MAJOR | `@Transactional` on Controller · `@Transactional` + `@Async` combination |
| **kafka** | ⚠️ WARNING | Zookeeper in docker-compose · `@KafkaListener` without `groupId` · no `@RetryableTopic` or DLQ |
| **observability** | ⚠️ WARNING | Endpoint methods with no structured logging (`log.info`, `log.warn`, etc.) |

---

## Example output

```
java-vibe-guard — vibe coding detector for Java/Spring Boot
Scanning: ./my-project  (47 files)

❌ CRITICAL: blocking .get() detected in @Scheduled method → OutboxPublisher.java:55
❌ CRITICAL: @Transactional + @Async — transaction will NOT propagate to async thread → PaymentService.java:88
❌ MAJOR: Controller accessing Repository directly (UserRepository) → UserController.java:23
❌ MAJOR: @Transactional on Controller method (move to Service layer) → PaymentController.java:12
⚠️  WARNING: @KafkaListener without @RetryableTopic or DLQ → OrderConsumer.java:34
⚠️  WARNING: Kafka using Zookeeper (deprecated — migrate to KRaft) → docker-compose.yml:8
⚠️  WARNING: Endpoint without structured logging → OrderController.java:67

──────────────────────────────────────────────────────────────
📊 Summary: 2 critical · 2 major · 3 warnings
──────────────────────────────────────────────────────────────

🚨 2 CRITICAL issue(s) found — fix before deploying to production.
```

---

## CLI options

```
Usage: java-vibe-guard <path> [options]

Arguments:
  path                   Path to Java/Spring Boot project to analyze

Options:
  -V, --version          show version
  --json                 output results as JSON (for CI parsing)
  --rule <name>          run only one rule: blocking | layers | kafka | transactions | observability
  --ignore <dirs>        comma-separated directories to exclude from scanning
  --no-color             disable colored output
  -h, --help             display help
```

### Excluding directories

Use `--ignore` to skip directories that intentionally deviate from production patterns:

```bash
# Skip educational lab directories and test fixtures
java-vibe-guard . --ignore labs,demos,test

# Skip generated code and legacy modules
java-vibe-guard . --ignore generated,legacy,sandbox
```

> **Note on educational projects:** `controller→repository` findings in lab or tutorial code are often intentional simplifications — the goal is demonstrating a single concept without the full service layer. Use `--ignore` to suppress findings in those directories and keep CI signal clean for production code only.

---

## CI integration

Exit code `1` if any CRITICAL finding, `0` otherwise.

```yaml
# GitHub Actions
- name: Vibe guard check
  run: npx java-vibe-guard . --json | tee vibe-report.json
  # Fails the build on CRITICAL findings
```

---

## Run a single rule

```bash
java-vibe-guard ./project --rule blocking
java-vibe-guard ./project --rule kafka
java-vibe-guard ./project --rule layers
java-vibe-guard ./project --rule transactions
java-vibe-guard ./project --rule observability
```

---

## Why these rules?

### blocking — Thread starvation in production
A `CompletableFuture.get()` inside a `@Scheduled` method blocks the scheduler thread. Under load, all scheduler threads exhaust and no scheduled task runs again. This is one of the most common silent failures in AI-generated Spring Boot code.

### layers — Architectural corruption
Controllers calling Repositories directly bypass all business logic, validation, caching, and event publishing in the Service layer. One sprint of vibe coding and your architecture is gone.

### transactions — The silent rollback trap
`@Transactional` on a Controller means the transaction spans the entire HTTP request including serialization — holding database connections longer than necessary. `@Transactional` + `@Async` is worse: Spring creates a new thread for `@Async`, which has no transaction context. The code runs, nothing fails, and your data is silently inconsistent.

### kafka — At-least-once with zero guarantees
A Kafka consumer without `groupId` creates a random group on every restart — it will reprocess all messages from the beginning. Without `@RetryableTopic`, one poison pill message retries forever and blocks partition progress.

### observability — Black box endpoints
Without structured logging at endpoints, debugging production incidents requires re-deployment. One `log.info` with the correlation ID costs nothing. The absence costs hours.

---

## Requirements

- Node.js 18+
- A Java/Spring Boot project to scan

---

## License

MIT
