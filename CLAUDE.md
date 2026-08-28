# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Visão geral

Microserviço de envio assíncrono de e-mails. Java 25 + Spring Boot 4.0.2, arquitetura hexagonal.
Fluxo: `POST multipart` → persiste `PENDING` + sobe anexos no storage S3 (Ceph RGW / MinIO) → publica evento no RabbitMQ → consumer baixa anexos, envia via SMTP e marca `SENT`; após esgotar retries a mensagem vai para a DLQ e é marcada `FAILED`.

Regras de estilo/arquitetura do projeto estão em `.agent/rules/java-moderno.md` (records para DTOs/VOs, domínio livre de framework, Lombok restrito a `@Getter`/`@Slf4j`/`@RequiredArgsConstructor`, `ddl-auto=none`, Testcontainers para integração). Siga-as.

## Comandos

```bash
# Infra local (rabbitmq, minio + bucket mail-attachments, postgres)
docker compose up -d

# Build / run
./mvnw clean package
./mvnw spring-boot:run

# Testes
./mvnw test                                   # suíte unitária: rápida, sem infra, sem contexto Spring
./mvnw test -Dtest.excludedGroups= -Dgroups=integration   # só os @Tag("integration") (exigem Postgres)
./mvnw test -Dtest.excludedGroups=                        # tudo
./mvnw test -Dtest=EmailQueueConsumerTest#dlqDeveMarcarComoFalha
```

Surefire exclui `@Tag("integration")` por padrão (`test.excludedGroups` no pom), então `mvn test` é verde em qualquer máquina. Os testes tagueados (`MailsenderApplicationTests`, `EmailAsyncFlowIntegrationTest`) usam `@ActiveProfiles("test")` + `src/test/resources/application-test.properties`, que fornece os placeholders do `application.yml` no lugar do `.env` — ajuste as credenciais de datasource lá, não no `.env`.

App sobe na porta **8081**. `api.http` tem requests prontos (health, texto simples, HTML, múltiplos anexos) — também disponíveis como run configs do IntelliJ.

## Configuração / env

`application.yml` não tem defaults: **toda** variável é obrigatória. Elas são lidas por `spring-dotenv`, que carrega apenas o arquivo `.env` — crie o seu a partir de `.envsample` (único template versionado) antes de rodar.

Nenhum arquivo com valores reais é versionado: `.gitignore` ignora `.env*`, e `.env-local` (se existir na sua máquina) é local. Ao adicionar uma variável nova ao `application.yml`, acrescente a chave vazia em `.envsample` — é o único registro compartilhado do contrato de configuração.

`application-backup.txt` guarda uma versão anterior do yml com defaults inline — é a melhor referência dos valores esperados de cada variável.

O storage de anexos é lido de `ceph.*` (`S3StorageConfig`); o bloco `minio:` do `application.yml` está **órfão** — nenhum código o lê. Ao apontar para MinIO local, preencha as `CEPH_*`.

## Arquitetura

Camadas em `br.com.js.mailsender`:

- **domain** — `EmailMessage` (agregado rico, construtores privados + `create`/`reconstitute`, `markAsSent`/`markAsFailed` validando estado), `Email` (VO record com regex), `EmailAttachment`; ports `EmailRepository`, `EmailGateway`, `AttachmentStorageGateway`.
- **application** — `SendEmailUseCase` orquestra: converte `MultipartFile` → `EmailAttachment`, sobe anexos, persiste, publica evento.
- **infrastructure** — `EmailJpaAdapter` (mapeia domínio ⇄ `*JpaEntity`, sem JPA no domínio), `SpringEmailGateway` (MimeMessage), `S3AttachmentStorageAdapter` (AWS SDK v2 com `forcePathStyle`, apontando para Ceph RGW ou MinIO), `RabbitMQConfig`, `EmailQueueConsumer`.
- **presentation** — `EmailController` (`/api/v1/emails`, `multipart/form-data` via `@ModelAttribute`).

### Anexos

Bytes nunca trafegam pela fila nem pelo banco: o evento carrega só o `emailId`; o banco guarda `storage_path` (chave `{emailId}/{filename}` no bucket `mail-attachments`, hardcoded em `S3AttachmentStorageAdapter`); o consumer rehidrata os bytes via `storageGateway.download` e monta um `EmailMessage.reconstitute` temporário só para o gateway SMTP.

### Mensageria

`DirectExchange emails.exchange` com três filas: `emails.send.queue` (`emails.send.key`), DLQ `emails.send.dlq` (`emails.dlq.key`) e parking `emails.send.parking` (`emails.parking.key`).

Retry é do listener Spring (`spring.rabbitmq.listener.simple.retry`, 3 tentativas, backoff 3s × 2.0); ao esgotar, o `RepublishMessageRecoverer` publica explicitamente no destino — escolha deliberada para não depender dos argumentos `x-dead-letter-*` da fila já criada no broker.

O `messageRecoverer` **ramifica pela fila de origem** (`getConsumerQueue()`): falha na fila principal → DLQ; falha ao consumir a DLQ → parking. Isso é obrigatório, não cosmético: o recoverer vale para todos os `@RabbitListener`, então um destino fixo na DLQ faria uma mensagem que falha no `consumeDlq` ser republicada na própria DLQ, em loop infinito. A parking queue não tem consumidor — é fim de linha para inspeção manual. `consumeDlq` então marca `FAILED`. **Os dois listeners** guardam a mesma condição — só agem sobre `PENDING` e ignoram (log + return) qualquer outro status, para que entrega duplicada ou DLQ após envio não estoure `IllegalStateException` no listener. `consumeDlq` vai além e **nunca propaga**: id inexistente é logado e descartado, porque não há fila atrás da DLQ para absorver a exceção — só `consume` lança (de propósito, para acionar o retry).

Alterar nomes/argumentos de fila em `RabbitMQConfig` não recria filas existentes no broker (RabbitMQ rejeita redeclaração divergente) — apague a fila no management UI (`:15672`) ao mudar argumentos. Filas novas (como a parking) são declaradas normalmente no startup.

### Persistência

Flyway com `baseline-on-migrate=true`; migrations em `src/main/resources/db/migration` (`V1__Initial_setup.sql`). Toda mudança de schema é migration nova — `ddl-auto=none`. O pom tem dependências Oracle/`flyway-database-oracle` comentadas: o projeto foi escrito para trocar de banco, evite SQL específico de Postgres nas migrations sem necessidade.

`EmailJpaRepository.findById` usa `@EntityGraph(attributePaths = "attachments")` para evitar lazy-loading fora de transação no consumer.

## Cuidados ao editar

- `@Transactional` em métodos **privados** (`SendEmailUseCase.getSavedEmail`, `EmailQueueConsumer.updateEmailStatus`, `getEmailMessage`) é ignorado pelo proxy do Spring — hoje funciona por causa do `saveAndFlush` no adapter. Se for mexer em transacionalidade nessas classes, mova a anotação para método público de um bean colaborador em vez de "consertar" no lugar.
- `EmailJpaAdapter.toEntity` cria entidades de anexo **novas** a cada `save`; combinado com `orphanRemoval`, atualizar um e-mail existente recria as linhas de anexo.
- `EmailJpaEntity.body` está anotado com `columnDefinition = "CLOB"` enquanto a migration usa `TEXT` (resquício da variante Oracle). Não deixe o Hibernate validar/gerar DDL.
- `@AutoConfigureMockMvc` **não existe** no `spring-boot-starter-test` 4.0.2 (saiu para outro módulo). Monte o `MockMvc` com `MockMvcBuilders.webAppContextSetup(context)`, como em `EmailAsyncFlowIntegrationTest`.
- A suíte unitária cobre domínio, use case, consumer e os dois adapters com Mockito (sem contexto Spring, sem infra). `EmailAsyncFlowIntegrationTest` mocka SMTP/storage/broker e chama `EmailQueueConsumer` direto, mas usa Postgres real; não há Testcontainers no projeto ainda.
