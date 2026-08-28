# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Visão geral

Microserviço de envio assíncrono de e-mails. Java 25 + Spring Boot 4.0.2, arquitetura hexagonal.
Fluxo: `POST multipart` → persiste `PENDING` + sobe anexos no MinIO/S3 → publica evento no RabbitMQ → consumer baixa anexos, envia via SMTP e marca `SENT`; após esgotar retries a mensagem vai para a DLQ e é marcada `FAILED`.

Regras de estilo/arquitetura do projeto estão em `.agent/rules/java-moderno.md` (records para DTOs/VOs, domínio livre de framework, Lombok restrito a `@Getter`/`@Slf4j`/`@RequiredArgsConstructor`, `ddl-auto=none`, Testcontainers para integração). Siga-as.

## Comandos

```bash
# Infra local (rabbitmq, minio + bucket mail-attachments, postgres)
docker compose up -d

# Build / run
./mvnw clean package
./mvnw spring-boot:run

# Testes
./mvnw test                                   # NÃO roda *IT (surefire default não inclui o sufixo IT)
./mvnw test -Dtest=EmailAsyncFlowIT            # roda o teste de integração explicitamente
./mvnw test -Dtest=EmailAsyncFlowIT#shouldProcessEmailAsynchronouslyWithAttachments
```

App sobe na porta **8081**. `api.http` tem requests prontos (health, texto simples, HTML, múltiplos anexos) — também disponíveis como run configs do IntelliJ.

## Configuração / env

`application.yml` não tem defaults: **toda** variável é obrigatória. Elas são lidas por `spring-dotenv`, que carrega apenas o arquivo `.env` — crie o seu a partir de `.envsample` (único template versionado) antes de rodar.

Nenhum arquivo com valores reais é versionado: `.gitignore` ignora `.env*`, e `.env-local` (se existir na sua máquina) é local. Ao adicionar uma variável nova ao `application.yml`, acrescente a chave vazia em `.envsample` — é o único registro compartilhado do contrato de configuração.

Pegadinha conhecida: `application.yml` referencia `SPRING_JPA_PROPERTIES_HIBERNATE_DIALECT`, que não existe em `.envsample`. `application-backup.txt` guarda a versão anterior do yml (com defaults inline) e é útil como referência dos valores esperados.

## Arquitetura

Camadas em `br.com.js.mailsender`:

- **domain** — `EmailMessage` (agregado rico, construtores privados + `create`/`reconstitute`, `markAsSent`/`markAsFailed` validando estado), `Email` (VO record com regex), `EmailAttachment`; ports `EmailRepository`, `EmailGateway`, `AttachmentStorageGateway`.
- **application** — `SendEmailUseCase` orquestra: converte `MultipartFile` → `EmailAttachment`, sobe anexos, persiste, publica evento.
- **infrastructure** — `EmailJpaAdapter` (mapeia domínio ⇄ `*JpaEntity`, sem JPA no domínio), `SpringEmailGateway` (MimeMessage), `S3AttachmentStorageAdapter` (AWS SDK v2 apontando para MinIO, `forcePathStyle`), `RabbitMQConfig`, `EmailQueueConsumer`.
- **presentation** — `EmailController` (`/api/v1/emails`, `multipart/form-data` via `@ModelAttribute`).

### Anexos

Bytes nunca trafegam pela fila nem pelo banco: o evento carrega só o `emailId`; o banco guarda `storage_path` (chave `{emailId}/{filename}` no bucket `mail-attachments`, hardcoded em `S3AttachmentStorageAdapter`); o consumer rehidrata os bytes via `storageGateway.download` e monta um `EmailMessage.reconstitute` temporário só para o gateway SMTP.

### Mensageria

`DirectExchange emails.exchange` → fila `emails.send.queue` (routing key `emails.send.key`) e DLQ `emails.send.dlq` (`emails.dlq.key`). Retry é do listener Spring (`spring.rabbitmq.listener.simple.retry`, 3 tentativas, backoff 3s × 2.0); ao esgotar, o `RepublishMessageRecoverer` publica explicitamente na DLQ — escolha deliberada para não depender dos argumentos `x-dead-letter-*` da fila já criada no broker. `consumeDlq` então marca `FAILED`. O consumer é idempotente: ignora mensagens cujo status já não é `PENDING`.

Alterar nomes/argumentos de fila em `RabbitMQConfig` não recria filas existentes no broker (RabbitMQ rejeita redeclaração divergente) — apague a fila no management UI (`:15672`) ao mudar argumentos.

### Persistência

Flyway com `baseline-on-migrate=true`; migrations em `src/main/resources/db/migration` (`V1__Initial_setup.sql`). Toda mudança de schema é migration nova — `ddl-auto=none`. O pom tem dependências Oracle/`flyway-database-oracle` comentadas: o projeto foi escrito para trocar de banco, evite SQL específico de Postgres nas migrations sem necessidade.

`EmailJpaRepository.findById` usa `@EntityGraph(attributePaths = "attachments")` para evitar lazy-loading fora de transação no consumer.

## Cuidados ao editar

- `@Transactional` em métodos **privados** (`SendEmailUseCase.getSavedEmail`, `EmailQueueConsumer.updateEmailStatus`, `getEmailMessage`) é ignorado pelo proxy do Spring — hoje funciona por causa do `saveAndFlush` no adapter. Se for mexer em transacionalidade nessas classes, mova a anotação para método público de um bean colaborador em vez de "consertar" no lugar.
- `EmailJpaAdapter.toEntity` cria entidades de anexo **novas** a cada `save`; combinado com `orphanRemoval`, atualizar um e-mail existente recria as linhas de anexo.
- `EmailJpaEntity.body` está anotado com `columnDefinition = "CLOB"` enquanto a migration usa `TEXT` (resquício da variante Oracle). Não deixe o Hibernate validar/gerar DDL.
- `EmailAsyncFlowIT` mocka `ConnectionFactory` para não exigir RabbitMQ e chama `EmailQueueConsumer` diretamente; ainda precisa de Postgres real de pé. `S3AttachmentStorageAdapterTest` precisa de MinIO real (cria o bucket se faltar) — nenhum dos dois usa Testcontainers hoje.
