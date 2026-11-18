# TaskFlow

[![CI](https://github.com/Aayaman/taskflow/actions/workflows/ci.yml/badge.svg)](https://github.com/Aayaman/taskflow/actions/workflows/ci.yml)

Distributed background job processing platform built with Java, Spring Boot, PostgreSQL and Redis.

## Prerequisites

- JDK 21
- Docker with Compose (Docker Desktop, Colima, or any compatible runtime)

Maven is not required; the project ships with the Maven Wrapper (`./mvnw`).

## Quickstart

```sh
git clone https://github.com/Aayaman/taskflow.git && cd taskflow
cp .env.example .env              # then set POSTGRES_PASSWORD and REDIS_PASSWORD
docker compose up -d --wait
./mvnw spring-boot:run
curl localhost:8080/actuator/health
```

Submit a job and fetch it:

```sh
curl -i localhost:8080/api/v1/jobs \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 3f0c1d52-order-12345' \
  -d '{"type": "SEND_EMAIL", "payload": {"to": "user-42"}}'
curl localhost:8080/api/v1/jobs/<id from the response>
```

Generate passwords with `openssl rand -hex 24`. Never commit `.env`.

Local services listen on `127.0.0.1` only:

| Service    | Host port |
|------------|-----------|
| PostgreSQL | 15432     |
| Redis      | 6379      |

## API

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/jobs` | Submit a job. Returns `201` and a `Location` header. With an `Idempotency-Key`, a repeated request returns `200` and the original job. |
| `GET /api/v1/jobs/{id}` | Get a job and its status |

Errors use RFC 9457 Problem Details (`application/problem+json`).
While the application runs, the OpenAPI spec is at [`/v3/api-docs`](http://localhost:8080/v3/api-docs) and Swagger UI at [`/swagger-ui.html`](http://localhost:8080/swagger-ui.html).

## Tests

```sh
./mvnw test     # unit tests, no containers needed
./mvnw verify   # unit + integration tests (requires Docker)
```

Integration tests start their own PostgreSQL and Redis with Testcontainers and do not need `.env`.

## Configuration

All settings come from environment variables; see [`.env.example`](.env.example).
For local development, `.env` is loaded automatically. Real environment variables take precedence.

## Roadmap

Currently complete: **M0 (project foundation)** and **M1 (job submission and persistence)**. Next: **M2 (PostgreSQL-only queue and workers)**.
See [the architecture overview](docs/architecture.md#roadmap) for all milestones and the reasoning behind their order.

## Documentation

- [Architecture](docs/architecture.md): what exists today, the target design, and the roadmap
- [Design decisions](docs/design-decisions.md): what was decided, the alternatives, and why

## License

[MIT](LICENSE)
