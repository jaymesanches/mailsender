# MailSender

Serviço de envio assíncrono de e-mails com anexos. Recebe uma requisição HTTP, salva o e-mail e seus anexos, publica um evento em fila e processa o envio via SMTP em background, com retry e dead-letter queue em caso de falha.

Arquitetura hexagonal (Ports & Adapters) — detalhes em [CLAUDE.md](CLAUDE.md).

## Pré-requisitos

- Java 25
- Maven (ou use o wrapper `./mvnw`)
- Docker + Docker Compose

## 1. Configurar variáveis de ambiente

Copie o template e preencha os valores:

```bash
cp .envsample .env
```

Para rodar tudo localmente com a infraestrutura do `compose.yaml` abaixo, use estes valores:

```dotenv
# RabbitMQ
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_USERNAME=guest
RABBITMQ_PASSWORD=guest

# Mail (aponte para um servidor SMTP acessível; não faz parte do compose deste projeto)
MAIL_HOST=localhost
MAIL_PORT=1025
MAIL_SMTP_AUTH=false
MAIL_SMTP_STARTTLS=false

# Datasource
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/mailsender_db
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=postgres

# MinIO
MINIO_ENDPOINT=http://localhost:9000
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin
```

> `.env` nunca deve ser commitado (já está no `.gitignore`). Se precisar testar contra um SMTP real, aponte `MAIL_HOST`/`MAIL_PORT` para ele.

## 2. Subir a infraestrutura

```bash
docker compose up -d
```

Isso sobe quatro serviços definidos em `compose.yaml`:

| Serviço | Imagem | Portas | Papel |
|---|---|---|---|
| `rabbitmq` | `rabbitmq:4.0-management` | 5672 (AMQP), 15672 (UI) | Fila de envio assíncrono |
| `minio` | `minio/minio` | 9000 (API S3), 9001 (Console) | Storage dos anexos |
| `createbuckets` | `minio/mc` | — | Cria o bucket `mail-attachments` automaticamente e sai |
| `postgres` | `postgres:17-alpine` | 5432 | Persistência do status dos e-mails |

Os dados de cada serviço (fila, objetos, tabelas) ficam em volumes nomeados (`rabbitmq-data`, `minio-data`, `postgres-data`), sobrevivendo a um `docker compose down` (sem `-v`).

Verifique que todos os serviços estão saudáveis:

```bash
docker compose ps
```

### RabbitMQ

- Management UI: http://localhost:15672 (usuário/senha: `guest` / `guest`)
- Filas relevantes (criadas automaticamente pela aplicação na primeira execução, via `RabbitMQConfig`):
  - `emails.send.queue` — fila principal de envio
  - `emails.send.dlq` — dead-letter queue, recebe mensagens após 3 tentativas falhas (backoff exponencial 3s → 6s → 12s)
  - Exchange: `emails.exchange`
- Na aba **Queues** do management UI dá pra acompanhar profundidade da fila, taxa de consumo e inspecionar mensagens presas na DLQ.

### MinIO

- Console: http://localhost:9001 (usuário/senha: `minioadmin` / `minioadmin`)
- API S3: http://localhost:9000
- Bucket `mail-attachments` é criado automaticamente pelo serviço `createbuckets` — os anexos enviados via API aparecem lá antes do e-mail ser processado.

## 3. Rodar a aplicação

```bash
./mvnw spring-boot:run
```

A API sobe em `http://localhost:8081`.

## 4. Testar o fluxo

Use o arquivo [`api.http`](api.http) (compatível com a extensão REST Client do VS Code / IntelliJ HTTP Client) para:

1. Checar o health check (`GET /api/v1/emails/health`)
2. Enviar um e-mail simples, um HTML, ou um com múltiplos anexos (`POST /api/v1/emails`, multipart/form-data)

O fluxo esperado:

```
POST /api/v1/emails → anexos sobem pro MinIO → e-mail salvo como PENDING → evento publicado no RabbitMQ
  ← resposta imediata {id, PENDING}

[Async] Consumer processa a fila → baixa anexos do MinIO → envia via SMTP → status vira SENT
  Se falhar 3x → mensagem vai pra DLQ → status vira FAILED
```

Acompanhe o status consultando o registro do e-mail pelo `id` retornado, ou observando a tabela no Postgres.

## Troubleshooting

- **Porta já em uso** (5672, 15672, 9000, 9001, 5432, 8081): outro processo/serviço já está usando a porta — pare-o ou ajuste o mapeamento em `compose.yaml`.
- **Bucket `mail-attachments` não existe**: o serviço `createbuckets` roda uma vez e sai; confira `docker compose logs createbuckets`. Ele só roda depois que o `minio` reporta saudável.
- **Aplicação não sobe / erro de variável de ambiente**: confirme que `.env` existe na raiz do projeto e tem todos os campos de `.envsample` preenchidos.
- **E-mail fica PENDING e nunca sai**: verifique se o worker consumiu a mensagem no RabbitMQ Management UI (fila `emails.send.queue`); se caiu na DLQ (`emails.send.dlq`), o status já deve estar `FAILED` — confira os logs da aplicação para a causa raiz (geralmente SMTP inacessível).
- **Quer recomeçar do zero**: `docker compose down -v` remove os volumes (perde filas, buckets e dados do Postgres).