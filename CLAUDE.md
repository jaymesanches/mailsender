# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build
mvn clean install

# Run
mvn spring-boot:run

# Run all tests
mvn test

# Start infrastructure dependencies
docker compose up -d
```

Docker Compose brings up: RabbitMQ (5672/15672), MinIO (9000/9001), PostgreSQL (5432).

## Architecture

Hexagonal architecture (Ports & Adapters) with four layers:

- **presentation** — REST controllers (`EmailController`: `POST /api/v1/emails` multipart/form-data)
- **application** — Use cases (`SendEmailUseCase`) orchestrate the full flow
- **domain** — Entities (`EmailMessage`, `EmailAttachment`) and port interfaces (`EmailGateway`, `EmailRepository`, `AttachmentStorageGateway`)
- **infrastructure** — Adapters: Spring Mail (SMTP), RabbitMQ consumer, S3/MinIO storage, PostgreSQL via JPA

### Async Email Flow

```
POST /api/v1/emails
  → SendEmailUseCase
      → upload attachments to MinIO
      → save EmailMessage (status: PENDING) to PostgreSQL
      → publish EmailEnqueuedEvent to RabbitMQ
  ← returns {id, PENDING}

[Async]
  RabbitMQ → EmailQueueConsumer
      → fetch EmailMessage from DB
      → download attachments from MinIO
      → send via JavaMailSender (SMTP)
      → update status to SENT
  On failure (3 retries, exponential backoff 3s→6s→12s):
      → publish to DLQ (emails.send.dlq)
      → update status to FAILED
```

Email status lifecycle: `PENDING → SENT` or `PENDING → FAILED`

## Configuration

External dependencies are configured in `src/main/resources/application.properties`. Sensitive credentials can be overridden via `.env` (loaded by spring-dotenv; see `.env-sample`).

Key properties:
- `spring.rabbitmq.*` — RabbitMQ connection and listener concurrency (2 base, 5 max, prefetch 1)
- `minio.*` — MinIO/S3 endpoint and credentials
- `spring.mail.*` — SMTP host (no auth required in dev)
- `spring.datasource.*` — PostgreSQL (`mailsender_db`), DDL auto: update

## Testing

Integration tests live in `src/test/java/.../integration/` and use Awaitility for async assertions. Infrastructure tests (e.g., `S3AttachmentStorageAdapterTest`) test adapters in isolation.

The `api.http` file in the project root contains ready-to-run HTTP requests for manual testing.

## Tech Stack

- Java 25, Spring Boot 4.0.2, Spring Modulith 2.0.2
- Spring Web, Data JPA, AMQP (RabbitMQ), JavaMail, AWS SDK S3
- Lombok, Jackson DateTime, spring-dotenv
