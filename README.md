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

Generate passwords with `openssl rand -hex 24`. Never commit `.env`.

Local services listen on `127.0.0.1` only:

| Service    | Host port |
|------------|-----------|
| PostgreSQL | 15432     |
| Redis      | 6379      |

## Tests

```sh
./mvnw test     # unit tests, no containers needed
./mvnw verify   # unit + integration tests (requires Docker)
```

Integration tests start their own PostgreSQL and Redis with Testcontainers and do not need `.env`.

## Configuration

All settings come from environment variables; see [`.env.example`](.env.example).
For local development, `.env` is loaded automatically. Real environment variables take precedence.

## Documentation

- [Design decisions](docs/design-decisions.md): what was decided, the alternatives, and why

## License

[MIT](LICENSE)
