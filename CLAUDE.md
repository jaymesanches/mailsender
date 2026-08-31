# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Visão geral

Microserviço de envio assíncrono de e-mails. Java 25 + Spring Boot 4.0.2, arquitetura hexagonal.
Fluxo: `POST multipart` → persiste `PENDING` + sobe anexos no storage S3 (Ceph RGW / MinIO) → publica evento no RabbitMQ → consumer baixa anexos, envia via SMTP e marca `SENT`. Falha transitória esgota o retry, vai para a DLQ e vira `FAILED` (**reenviável**); falha permanente vira `REJECTED` na hora, sem retry.

`MANUAL.md` narra o fluxo passo a passo e o setup do ambiente — consulte-o quando precisar do *porquê* de uma etapa; este arquivo é o resumo acionável.

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

**Tamanho.** `mailsender.attachments.max-message-size` é o limite do **provedor** (25MB, o número do admin center), não o do upload: `AttachmentProperties.maxRawAttachmentBytes()` divide por `encodingOverhead` (1.37) porque o Exchange limita a mensagem MIME codificada e base64 infla 1/3. Dá ~18MB de bytes crus. Não troque isso por um 18MB cravado — o derivado acompanha quando o admin libera mais.

`SendEmailUseCase.validarTamanho` checa a **soma** com `getSize()` **antes** de `getBytes()`, para não alocar o anexo só para descartar, e lança `AttachmentTooLargeException` → 413. `spring.servlet.multipart` (20MB/22MB) é só a guarda de memória externa, folgada de propósito para que a mensagem explicativa venha do use case; `MaxUploadSizeExceededException` também vira 413 no `ApiExceptionHandler` (sem o handler seria 500).

### Mensageria

`DirectExchange emails.exchange` com quatro filas: `emails.send.queue` (`emails.send.key`), DLQ `emails.send.dlq` (`emails.dlq.key`), parking `emails.send.parking` (`emails.parking.key`) e espera `emails.send.wait` (`emails.wait.key`, TTL 60s + DLX de volta para a principal).

Retry é do listener Spring (`spring.rabbitmq.listener.simple.retry`): `max-retries: 3` são **4 entregas** (1 inicial + 3 retries, conforme o `RetryPolicy` do Spring Framework 7), em t=0/3s/9s/19s — o terceiro intervalo é capado pelo `max-interval` default de 10s. Retry `stateless`: bloqueia a thread do consumidor, não devolve ao broker. Ao esgotar, o `RepublishMessageRecoverer` publica explicitamente no destino — escolha deliberada para não depender dos argumentos `x-dead-letter-*` da fila já criada no broker.

O `messageRecoverer` **ramifica pela fila de origem** (`getConsumerQueue()`): falha na fila principal → DLQ; falha ao consumir a DLQ → parking. Isso é obrigatório, não cosmético: o recoverer vale para todos os `@RabbitListener`, então um destino fixo na DLQ faria uma mensagem que falha no `consumeDlq` ser republicada na própria DLQ, em loop infinito. A parking tem consumidor que **só loga em ERROR e nunca altera status**: chegar lá significa que o estado no banco não é confiável, e marcar `FAILED` faria o reenvio duplicar uma entrega. `consumeDlq` então marca `FAILED`. **Os dois listeners** guardam a mesma condição — só agem sobre `PENDING` e ignoram (log + return) qualquer outro status, para que entrega duplicada ou DLQ após envio não estoure `IllegalStateException` no listener. `consumeDlq` vai além e **nunca propaga**: id inexistente é logado e descartado, porque não há fila atrás da DLQ para absorver a exceção — só `consume` lança (de propósito, para acionar o retry).

Alterar nomes/argumentos de fila em `RabbitMQConfig` não recria filas existentes no broker (RabbitMQ rejeita redeclaração divergente) — apague a fila no management UI (`:15672`) ao mudar argumentos. Filas novas (como a parking) são declaradas normalmente no startup.

### Throttling e pool de contas

O provedor (Exchange Online) limita ~30 msg/min e ~10.000 destinatários/dia **por caixa**. `SpringEmailGateway` classifica pela classe do código estendido (RFC 3463): `4.x.x` → `ThrottledMailFailure`, `5.x.x` ou `getInvalidAddresses()` não vazio → `PermanentMailFailure`, sem código estendido → `TransientMailFailure`.

**Throttling não é falha do e-mail.** O consumidor manda para `emails.send.wait` (TTL 60s, DLX devolve à fila principal) e **não toca em `status` nem em `attempts`**. O contador de ciclos vai no header `x-throttle-cycle`; passando de `mailsender.throttle.max-cycles` (default 10, `@Value` no consumidor) vira `FAILED`, que é reenviável. Nunca reintroduza throttling na escada de retry: ela se esgota em 19s e um limite por minuto precisa de ~60s.

Contas são intercambiáveis: **uma fila, consumidores concorrentes**, nunca fila por conta. `MailAccountPool.acquire()` roda um índice rotativo e consulta o `SendRateLimiter` (janela de 60s por conta); vazio → `ThrottledMailFailure`. `mailsender.accounts[]` vazio cai no `spring.mail.*` autoconfigurado como conta `default` (dev local com MailHog).

`EmailGateway.send` **devolve o nome da conta** que entregou. As três exceções de envio herdam de `MailFailure`, que carrega `account()` — assim `last_account` é gravada em **qualquer desfecho**, não só no sucesso.

O caminho não-óbvio é o `FAILED` vindo da DLQ: `consumeDlq` só recebe o id, então quem grava é `recordAttemptFailure(conta, erro)` **durante a tentativa**, sem mudar o status; depois `markAsFailed()` (sem argumento) só vira a chave, preservando o diagnóstico. Não volte a passar o motivo no `markAsFailed`: isso apagaria o erro real do SMTP. A gravação do diagnóstico é best-effort e nunca pode impedir o retry.

Cada conta recebe timeouts de SMTP por padrão (`connectiontimeout` 5s, `timeout`/`writetimeout` 10s) — sem eles o JavaMail espera para sempre e uma conexão pendurada prende a thread do consumidor. O mapa `properties` por conta sobrescreve isso e qualquer outra propriedade JavaMail; chave com ponto **exige colchetes** no YAML (`"[mail.smtp.timeout]"`), senão o binder trata o ponto como aninhamento.

`MailAccountPool` loga um WARN no boot (`Controle de taxa EM MEMORIA`) enquanto o limiter de memória estiver ativo e houver limite real — não apague sem trocar a implementação. `InMemorySendRateLimiter` conta **por processo**: ao passar de uma instância, trocar por implementação distribuída ou particionar contas por instância, senão o limite do provedor estoura.

### Expurgo de anexos

Job diário (`PurgeAttachmentsJob`, `mailsender.purge.*`) apaga os bytes do storage 90 dias após `created_at` e anula o `storagePath`; a linha de `emails` fica. Corte por `created_at` porque `sent_at` é nulo em `FAILED`/`REJECTED`.

**A invariante mora no agregado**: `EmailMessage.isPurgeable()` = não-`PENDING` e não-retentável. Nunca expurgue por query sozinha — o reenvio não re-sobe anexo, então soltar bytes de e-mail ainda reenviável o quebra em silêncio. `PurgeAttachmentsUseCase` checa antes de apagar qualquer byte e apaga **storage primeiro, banco depois** (a ordem inversa deixaria bytes órfãos).

`storagePath` nulo = bytes expurgados; o consumidor encerra em `REJECTED` se topar com isso.

### Estados e reenvio

`PENDING → SENT | FAILED | REJECTED`, e essas três transições só partem de `PENDING`. A quarta, `markForRetry()`, é a exceção: exige `FAILED` **e** `attempts < EmailMessage.MAX_ATTEMPTS` (3), incrementa `attempts` e devolve a `PENDING`.

`FAILED` é reenviável; `SENT` e `REJECTED` são terminais. `SpringEmailGateway` classifica a falha do SMTP em `TransientMailFailure` / `PermanentMailFailure` (destinatário presente em `SendFailedException.getInvalidAddresses()`), mantendo o detalhe do provedor na infraestrutura — o consumidor decide o status só pelo tipo da exceção.

Dois gatilhos de reenvio, ambos no `ResendEmailUseCase`: `POST /api/v1/emails/{id}/reenvio` (202; 409 em transição inválida, via `ApiExceptionHandler`) e `RetryFailedEmailsJob` (`@Scheduled`, `mailsender.retry.interval`). Reenvio **não re-sobe anexo** — o `storagePath` segue válido.

`EmailDispatcher` (port) é o único caminho para a fila: `SendEmailUseCase` e `ResendEmailUseCase` não conhecem RabbitMQ.

### Persistência

Flyway com `baseline-on-migrate=true`; migrations em `src/main/resources/db/migration` (`V1__Initial_setup.sql`). Toda mudança de schema é migration nova — `ddl-auto=none`. O pom tem dependências Oracle/`flyway-database-oracle` comentadas: o projeto foi escrito para trocar de banco, evite SQL específico de Postgres nas migrations sem necessidade.

`EmailJpaRepository.findById` usa `@EntityGraph(attributePaths = "attachments")` para evitar lazy-loading fora de transação no consumer.

## Cuidados ao editar

- **O projeto não tem nenhum `@Transactional`, e isso é deliberado.** Toda operação é uma única chamada de repositório, e `SimpleJpaRepository.save`/`saveAndFlush` já abrem transação própria. Não reintroduza a anotação num método chamado via `this` de dentro do mesmo bean: o proxy não intercepta self-invocation, e mudar a visibilidade (`private` → `protected`/`public`) não resolve. A primeira fronteira transacional real só passa a fazer sentido quando uma operação tiver **mais de uma escrita** — e aí ela vai num método público de um bean colaborador.
- `EmailJpaAdapter.toEntity` cria entidades de anexo **novas** a cada `save`; combinado com `orphanRemoval`, atualizar um e-mail existente recria as linhas de anexo.
- `EmailJpaEntity.body` está anotado com `columnDefinition = "CLOB"` enquanto a migration usa `TEXT` (resquício da variante Oracle). Não deixe o Hibernate validar/gerar DDL.
- `@AutoConfigureMockMvc` **não existe** no `spring-boot-starter-test` 4.0.2 (saiu para outro módulo). Monte o `MockMvc` com `MockMvcBuilders.webAppContextSetup(context)`, como em `EmailAsyncFlowIntegrationTest`.
- A suíte unitária cobre domínio, use case, consumer e os dois adapters com Mockito (sem contexto Spring, sem infra). `EmailAsyncFlowIntegrationTest` mocka SMTP/storage/broker e chama `EmailQueueConsumer` direto, mas usa Postgres real; não há Testcontainers no projeto ainda.
