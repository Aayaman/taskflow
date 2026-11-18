# Design Decisions

A log of significant technical decisions: the context, what was decided, the alternatives considered, and the consequences.
New decisions are appended; superseded ones are marked, not deleted.

| #  | Decision | Status | Date |
|----|----------|--------|------|
| 1  | [PostgreSQL is the source of truth; at-least-once execution](#1-postgresql-is-the-source-of-truth-at-least-once-execution) | Accepted | 2026-11-13 |
| 2  | [Modular monolith, packaged by feature](#2-modular-monolith-packaged-by-feature) | Accepted | 2026-11-13 |
| 3  | [Spring Boot 4 on Java 21](#3-spring-boot-4-on-java-21) | Accepted | 2026-11-13 |
| 4  | [Spring JDBC instead of JPA](#4-spring-jdbc-instead-of-jpa) | Accepted | 2026-11-13 |
| 5  | [Flyway migrations in a dedicated `taskflow` schema](#5-flyway-migrations-in-a-dedicated-taskflow-schema) | Accepted | 2026-11-14 |
| 6  | [Configuration through environment variables, failing fast on missing secrets](#6-configuration-through-environment-variables-failing-fast-on-missing-secrets) | Accepted | 2026-11-14 |
| 7  | [Pinned container images, identical in Compose and tests](#7-pinned-container-images-identical-in-compose-and-tests) | Accepted | 2026-11-14 |
| 8  | [Testcontainers for integration tests](#8-testcontainers-for-integration-tests) | Accepted | 2026-11-14 |
| 9  | [Local services bound to 127.0.0.1 on non-default ports](#9-local-services-bound-to-127001-on-non-default-ports) | Accepted | 2026-11-14 |
| 10 | [Health endpoint shows component status, never details](#10-health-endpoint-shows-component-status-never-details) | Accepted | 2026-11-14 |
| 11 | [Five job states, with time-based waiting](#11-five-job-states-with-time-based-waiting) | Accepted | 2026-11-14 |
| 12 | [Strict request parsing and Problem Details errors](#12-strict-request-parsing-and-problem-details-errors) | Accepted | 2026-11-16 |
| 13 | [Idempotent submission resolved by the database](#13-idempotent-submission-resolved-by-the-database) | Accepted | 2026-11-17 |

---

## 1. PostgreSQL is the source of truth; at-least-once execution

**Context.** TaskFlow uses both PostgreSQL and Redis. If an accepted job could exist only in Redis, a Redis restart or eviction could lose it. Separately, a worker can crash after performing a job's side effect but before recording completion, so the system cannot always know whether a job ran.

**Decision.**
- Every accepted job is persisted in PostgreSQL before it is acknowledged. PostgreSQL holds the authoritative job state.
- Redis, when introduced, only accelerates delivery. Losing Redis may slow processing but must never lose a job.
- Execution semantics are **at-least-once**. Job handlers are expected to be safe to retry.

**Alternatives considered.**
- *Redis as the primary queue:* fast, but durability depends on Redis persistence settings and makes job state harder to query and audit.
- *Exactly-once execution:* not achievable for arbitrary external side effects without cooperation from the systems being called. Claiming it would be misleading.

**Consequences.** Duplicate execution is possible and must be handled with idempotency keys, leases and atomic state transitions. Any write that spans PostgreSQL and Redis needs an explicit consistency mechanism (a transactional outbox).

## 2. Modular monolith, packaged by feature

**Context.** The system has distinct concerns (job lifecycle, delivery, workers), but it is built by one developer and does not yet need independent deployment or scaling of each concern.

**Decision.**
- One deployable application. Multiple instances, including instances running as workers, provide the distribution.
- Code is organised by feature (`job`, `worker`, …), not by layer. Each module owns its tables and exposes a small public API; internals stay in an `internal` sub-package.
- Packages are created when they get real code, not ahead of time.

**Alternatives considered.**
- *Microservices:* add network calls, deployment and consistency problems before the domain is understood.
- *Package by layer (`controller/`, `service/`, `repository/`):* scatters each feature across the codebase and makes module boundaries invisible.
- *Multiple Maven modules:* enforce boundaries at build time, but add overhead that is not justified yet.

**Consequences.** Boundaries are enforced by convention for now. Tooling such as Spring Modulith or ArchUnit can verify them once several modules exist.

## 3. Spring Boot 4 on Java 21

**Context.** The project requirements specify Java 21 (LTS) and Spring Boot 3 or later.

**Decision.**
- Spring Boot 4.1.2 on Java 21.
- The build fails on any JDK other than 21 (Maven Enforcer), so local builds and CI stay identical.
- The Maven Wrapper (`./mvnw`) is used, so no Maven installation is required.

**Alternatives considered.**
- *Spring Boot 3.5:* more tutorials available, but out of open-source support, and no longer offered by Spring Initializr.
- *Java 25 (the newest LTS, September 2025):* supported by Spring Boot 4. Java 21 was kept for its longer track record in libraries, CI images and hosting platforms. Moving to 25 later is a small, contained change.
- *Allowing any JDK ≥ 21:* compiling with `--release 21` on a newer JDK hides runtime differences.

**Consequences.** Some Boot 3 material no longer applies directly:
- Starters are renamed and split (`spring-boot-starter-webmvc`, `spring-boot-starter-flyway`).
- Testcontainers 2 moved some packages.
- Jackson 3 uses `tools.jackson.*` packages.

All dependency versions come from the Spring Boot BOM, the list of library versions Spring Boot tests together.

## 4. Spring JDBC instead of JPA

**Context.** A job queue's core operations are explicit SQL: claiming rows with `FOR UPDATE SKIP LOCKED`, conditional state transitions, batch updates and lease expiry queries.

**Decision.** Use Spring JDBC (`JdbcClient`) with hand-written SQL. No ORM (object-relational mapping) for now.

**Alternatives considered.**
- *Spring Data JPA / Hibernate:* reduces boilerplate for simple CRUD, but hides exactly the SQL, locking and flush timing that the queue's correctness depends on.
- *jOOQ:* type-safe SQL, but an extra dependency and code generation step that is not justified yet.

**Consequences.** More mapping code, but every query that matters is visible and reviewable. The decision will be revisited if plain CRUD starts to dominate.

## 5. Flyway migrations in a dedicated `taskflow` schema

**Context.** The database schema must evolve reproducibly across developer machines, CI and deployments.

**Decision.**
- Flyway runs SQL migrations from `src/main/resources/db/migration` at startup.
- All objects live in a dedicated `taskflow` schema, which Flyway creates. Application connections default to it.
- Migrations are forward-only: an applied migration is never edited, and fixes go in a new version.
- `flyway clean` is disabled.
- No placeholder migration exists. The first versioned migration will create the first real table.

**Alternatives considered.**
- *Liquibase:* more features (XML/YAML changelogs, rollbacks) than needed. Plain SQL is easier to review.
- *Hibernate `ddl-auto`:* not applicable without JPA, and unsafe for production schemas.
- *The default `public` schema:* since PostgreSQL 15, ordinary users can no longer create objects in it, and a named schema makes ownership explicit.

**Consequences.** Running migrations at startup is simple, and safe with several instances because Flyway takes a database lock. Production may later run migrations as a separate deployment step with a dedicated owner role.

## 6. Configuration through environment variables, failing fast on missing secrets

**Context.** Credentials must never be committed, and the same build must run locally, in CI and in production.

**Decision.**
- All settings come from environment variables. Docker Compose and Spring Boot read the same variable names.
- Locally, `.env` (git-ignored) is loaded automatically. `.env.example` documents the variables with empty placeholders.
- Non-secret settings have defaults; secrets never do.
- Startup aborts with an explicit message, before any connection is opened, if a required password is missing.

**Alternatives considered.**
- *Relying on Spring's placeholder resolution:* Spring Boot passes an unresolved `${POSTGRES_PASSWORD}` through as literal text. The result is a misleading authentication error, or for Redis no error until first use.
- *Profile-specific config files with credentials:* too easy to commit by mistake.
- *A secrets manager now:* useful for deployment (M8), unnecessary locally.

**Consequences.** `.env` must use plain `KEY=value` lines (no quotes, no `export`), because it is read both by Compose and as a Java properties file. Real environment variables always override `.env`.

## 7. Pinned container images, identical in Compose and tests

**Context.** Floating tags (`latest`, `18`) change silently and make environments drift apart.

**Decision.**
- Use exact tags: `postgres:18.7-trixie` and `redis:8.10.3-alpine`.
- The integration tests use the same tags, and a unit test fails the build if `compose.yaml` and the test configuration differ.
- PostgreSQL uses the Debian-based image; Redis uses Alpine.

**Alternatives considered.**
- *Floating major tags:* receive patches automatically, but make results non-reproducible.
- *Alpine for PostgreSQL:* a smaller image, but it uses the musl C library, whose text collation can differ from the glibc systems typically used in production. That would silently change `ORDER BY` results.

**Consequences.** Image upgrades are deliberate: change both places in one commit and let CI verify.

## 8. Testcontainers for integration tests

**Context.** Integration tests need real PostgreSQL and Redis, and must behave identically on every machine and in CI.

**Decision.**
- Integration tests (`*IT`) start their own containers with Testcontainers. `@ServiceConnection` wires the connections, so tests never depend on `.env` or on locally running services.
- Unit tests run under `./mvnw test` and need no Docker. Integration tests run under `./mvnw verify`.

**Alternatives considered.**
- *Testing against the Compose stack:* depends on local state and ports, and fails in CI unless Compose is started first.
- *Embedded or in-memory databases (H2, embedded Redis):* behave differently from production (SQL dialect, locking), which matters most for a queue.

**Consequences.** `verify` needs a Docker-compatible runtime and takes longer: test contexts with different configurations start their own containers.

## 9. Local services bound to 127.0.0.1 on non-default ports

**Context.** Docker publishes ports on all network interfaces by default, so a database could be reachable from any network the machine joins. Ports 5432 and 5433 are often already used by locally installed PostgreSQL servers, which can silently answer instead of the container.

**Decision.**
- Compose publishes ports on `127.0.0.1` only.
- PostgreSQL uses host port **15432**; Redis uses **6379**. Both can be overridden in `.env`.
- Redis requires a password and has append-only persistence enabled.

**Alternatives considered.**
- *Default port 5432:* conflicts with local PostgreSQL installations. Installers that find 5432 taken typically choose 5433 or 5434, so those are risky too.

**Consequences.** Containers are not reachable from other machines, which is intended for local development.

## 10. Health endpoint shows component status, never details

**Context.** Actuator can expose many endpoints, some of them sensitive (configuration, environment variables, beans).

**Decision.**
- Only `/actuator/health` is exposed over HTTP.
- It reports `UP`/`DOWN` for each component (`db`, `redis`) but hides details such as versions or database names.
- An integration test verifies this.

**Alternatives considered.**
- *Exposing details locally only (profile-based):* adds configuration paths without a current need.

**Consequences.** A Redis outage marks the application `DOWN` (HTTP 503), even though Redis is not used for processing yet. The liveness group does not include Redis, so an orchestrator would not restart the application because of it. Metrics (`/actuator/prometheus`) are planned for M6.

## 11. Five job states, with time-based waiting

**Context.** A job moves through waiting, execution and an outcome. A state model with many overlapping states makes transitions hard to reason about under concurrency, and mixing delivery details into job state lets the two disagree.

**Decision.**
- Five states: `PENDING`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`.
- Allowed transitions:

  | From | To | Reason |
  |---|---|---|
  | `PENDING` | `RUNNING` | A worker claims the job |
  | `PENDING` | `CANCELLED` | Cancelled before it started |
  | `RUNNING` | `SUCCEEDED` | Handler finished successfully |
  | `RUNNING` | `FAILED` | Permanent error, or retries exhausted |
  | `RUNNING` | `PENDING` | Retry after a failed attempt, or recovery after an expired lease |
  | `FAILED` | `PENDING` | Manual retry by an operator |

- Waiting for a retry and waiting for a scheduled time are both `PENDING` with a future `run_at`.
- `SUCCEEDED`, `FAILED` and `CANCELLED` are terminal: the system never moves a job out of them on its own. Only a manual retry can move a `FAILED` job.
- The rules live in one place (`JobStatus`). Persisted transitions must additionally be conditional on the current state in SQL, so that only one of several concurrent updates succeeds.

**Alternatives considered.**
- *A `QUEUED` state for "published to the queue":* whether an event reached Redis is a delivery detail. Storing it on the job lets the job and the queue disagree; it belongs to the outbox.
- *Separate `RETRY` and `SCHEDULED` states:* both mean "eligible at a later time", which `run_at` already expresses.
- *Separate `FAILED` and `DEAD_LETTER` states:* two terminal failure states with no behavioural difference.
- *Allowing `RUNNING` to `CANCELLED`:* interrupting a handler in the middle of an external call is unsafe. A cancellation request can be added later if needed.
- *A PostgreSQL enum type for the status column:* values cannot easily be removed or renamed. `text` with a `CHECK` constraint is easier to evolve.

**Consequences.** Retry, scheduling and recovery all reduce to "set `PENDING` and choose `run_at`". Cancelling a running job is not supported.

## 12. Strict request parsing and Problem Details errors

**Context.** The job API is the system's main entry point. Clients need precise, machine-readable errors, and must never be misled into thinking an unsupported option took effect.

**Decision.**
- Errors use RFC 9457 Problem Details (`application/problem+json`). Validation errors add an `errors` array of `{field, message}`.
- Unknown request fields are rejected with `400` and name the field. Spring Boot's default of silently ignoring them is turned off.
- Requests are validated with Jakarta Bean Validation. Rules that also exist as database constraints (job type format) are kept identical in both places.
- The job payload must be a JSON object of at most 64 KB, measured as compact UTF-8 JSON, so formatting whitespace does not count.
- Malformed bodies return a generic "not valid JSON" message rather than parser internals.

**Alternatives considered.**
- *Ignoring unknown fields:* lenient, but a client sending `priority` before priorities exist would believe it was applied.
- *Spring's default validation response:* it does not say which fields failed.
- *A custom error format:* RFC 9457 is a standard that clients and tools already understand.
- *Measuring the raw request size for the payload limit:* would count whitespace against the client.

**Consequences.** Clients cannot send fields ahead of server support; new optional fields remain backward compatible. The payload limit is checked after the body has been read, so it is a policy, not protection against very large requests. A hard request-size limit belongs at the reverse proxy and is planned with deployment (M8).

## 13. Idempotent submission resolved by the database

**Context.** A client that times out cannot tell whether its job was accepted. Retrying without protection can create the same job twice, and job handlers are only expected to tolerate re-execution, not duplicate submissions.

**Decision.**
- Clients may send an `Idempotency-Key` header (1 to 255 characters). Without it, every submission creates a job.
- A repeated key with the same type and payload returns `200` with the original job and its `Location`. The same key with a different type or payload returns `422`.
- Payloads are compared with PostgreSQL jsonb equality, so key order and whitespace do not matter.
- Uniqueness is enforced by a unique constraint. The insert uses `ON CONFLICT (idempotency_key) DO NOTHING`; when it reports a conflict, a second statement reads the existing job.
- The two statements deliberately run in separate READ COMMITTED transactions. A conflicting insert waits for the competing transaction to finish, and the following read, as a new statement, sees its committed row.
- Keys are global and kept forever for now.

**Alternatives considered.**
- *Check for the key, then insert:* two concurrent requests can both miss the key; one then fails on the unique constraint or, without it, a duplicate job is created.
- *Catching the unique-violation error:* works, but uses exceptions for an expected outcome and aborts the surrounding transaction.
- *Comparing payloads in Java:* would make the application, not the source of truth, define what "same request" means.
- *Returning `409` while the original request is still in progress:* unnecessary here, because the database already serializes the competing inserts.

**Consequences.** Retries are safe for clients that send a key. Keys are never released, so a client cannot reuse a key for a new job, and the table grows with every keyed submission; per-client scoping and key expiry are deferred until there is authentication and a retention policy.
