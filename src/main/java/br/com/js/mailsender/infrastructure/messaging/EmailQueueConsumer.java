package br.com.js.mailsender.infrastructure.messaging;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import br.com.js.mailsender.domain.model.EmailAttachment;
import br.com.js.mailsender.domain.model.EmailMessage;
import br.com.js.mailsender.domain.model.MailFailure;
import br.com.js.mailsender.domain.model.PermanentMailFailure;
import br.com.js.mailsender.domain.model.ThrottledMailFailure;
import br.com.js.mailsender.domain.ports.AttachmentStorageGateway;
import br.com.js.mailsender.domain.ports.EmailGateway;
import br.com.js.mailsender.domain.ports.EmailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailQueueConsumer {

    /** Depois disso o throttling deixou de ser pico: vira falha registrada. */
    static final int MAX_THROTTLE_CYCLES = 10;

    private final EmailRepository emailRepository;
    private final EmailGateway emailGateway;
    private final AttachmentStorageGateway storageGateway;
    private final RabbitEmailDispatcher dispatcher;

    @RabbitListener(queues = RabbitMQConfig.EMAIL_QUEUE)
    public void consume(EmailEnqueuedEvent event,
            @Header(name = RabbitMQConfig.THROTTLE_CYCLE_HEADER, required = false) Integer throttleCycle) {
        log.info("Consuming email sending task for ID: {}", event.emailId());

        var emailMessage = getEmailMessage(event);

        if (emailMessage.getStatus() != EmailMessage.EmailStatus.PENDING) {
            log.warn("Email {} is already in status {}. Skipping.", event.emailId(), emailMessage.getStatus());
            return;
        }

        try {
            var conta = emailGateway.send(withAttachmentContent(emailMessage));

            emailMessage.markAsSent(conta);
            emailRepository.save(emailMessage);
        } catch (PermanentMailFailure e) {
            // retentar nao muda o resultado: encerra em REJECTED sem passar pela DLQ
            log.error("Email {} rejeitado em definitivo pela conta {}: {}",
                    event.emailId(), e.account(), e.getMessage());
            emailMessage.markAsRejected(e.getMessage(), e.account());
            emailRepository.save(emailMessage);
        } catch (ThrottledMailFailure e) {
            aguardarJanela(event, emailMessage, throttleCycle, e);
        } catch (Exception e) {
            log.error("Transient error sending email {}, delegating to RabbitMQ retry policy", event.emailId(), e);
            registrarTentativa(emailMessage, e);
            throw new AmqpException("Failed to send email", e);
        }
    }

    /**
     * Grava conta e motivo da tentativa sem mudar o status, para que o consumidor da
     * DLQ — que so recebe o id — tenha o diagnostico. Best-effort de proposito:
     * falhar ao registrar nao pode impedir o retry da mensagem.
     */
    private void registrarTentativa(EmailMessage emailMessage, Exception e) {
        var conta = e instanceof MailFailure falha ? falha.account() : null;
        try {
            emailMessage.recordAttemptFailure(conta, e.getMessage());
            emailRepository.save(emailMessage);
        } catch (Exception ignored) {
            log.warn("Nao foi possivel registrar o diagnostico da tentativa do email {}", emailMessage.getId());
        }
    }

    /**
     * Throttling nao e falha do e-mail: nao mexe em status nem em attempts enquanto
     * espera. A mensagem vai para a sala de espera e volta sozinha em ~1 minuto.
     */
    private void aguardarJanela(EmailEnqueuedEvent event, EmailMessage emailMessage,
            Integer throttleCycle, ThrottledMailFailure e) {
        int ciclo = throttleCycle == null ? 0 : throttleCycle;

        if (ciclo >= MAX_THROTTLE_CYCLES) {
            log.error("Email {} throttled apos {} ciclos de espera. Registrando como falha.",
                    event.emailId(), ciclo);
            emailMessage.recordAttemptFailure(e.account(),
                    "Throttling persistente apos " + ciclo + " ciclos de espera");
            emailMessage.markAsFailed();
            emailRepository.save(emailMessage);
            return;
        }

        log.warn("Email {} throttled ({}). Aguardando a janela, ciclo {}.",
                event.emailId(), e.getMessage(), ciclo + 1);
        dispatcher.enqueueAfterWait(event.emailId(), ciclo + 1);
    }

    @RabbitListener(queues = RabbitMQConfig.DLQ_QUEUE)
    public void consumeDlq(EmailEnqueuedEvent event) {
        // Nao existe fila atras da DLQ: excecao aqui vira poison message, entao toda
        // condicao irrecuperavel e logada e a mensagem descartada em vez de propagada.
        var persistido = emailRepository.findById(event.emailId());

        if (persistido.isEmpty()) {
            log.error("Email {} chegou na DLQ mas nao existe no banco. Descartando.", event.emailId());
            return;
        }

        var emailMessage = persistido.get();

        if (emailMessage.getStatus() != EmailMessage.EmailStatus.PENDING) {
            log.warn("Email {} chegou na DLQ mas ja esta em {}. Skipping.", event.emailId(), emailMessage.getStatus());
            return;
        }

        log.error("Email processing failed after all retries. Moving to FAILED status. ID: {}", event.emailId());
        emailMessage.markAsFailed();
        emailRepository.save(emailMessage);
    }

    /**
     * Nao altera status de proposito: chegar aqui significa que nem o registro da
     * falha funcionou, logo nao se sabe se o e-mail saiu. Marcar FAILED poderia
     * mandar reenviar algo ja entregue — a decisao fica com um humano.
     */
    @RabbitListener(queues = RabbitMQConfig.PARKING_QUEUE)
    public void consumeParking(EmailEnqueuedEvent event) {
        log.error("Email {} estacionado: o registro da falha nao foi gravado e o estado no banco "
                + "nao e confiavel. Verifique manualmente se o e-mail foi enviado.", event.emailId());
    }

    private EmailMessage withAttachmentContent(EmailMessage emailMessage) {
        var attachmentsWithContent = emailMessage.getAttachments().stream()
                .map(att -> new EmailAttachment(att.getName(), att.getContentType(),
                        storageGateway.download(att.getStoragePath()), att.getStoragePath()))
                .toList();

        return EmailMessage.reconstitute(
                emailMessage.getId(),
                emailMessage.getTo(),
                emailMessage.getSubject(),
                emailMessage.getBody(),
                emailMessage.isHtml(),
                attachmentsWithContent,
                emailMessage.getStatus(),
                emailMessage.getCreatedAt(),
                emailMessage.getSentAt(),
                emailMessage.getLastAccount(),
                emailMessage.getAttempts(),
                emailMessage.getLastError());
    }

    /** Lanca de proposito quando nao acha: e o que aciona o retry do listener. */
    private EmailMessage getEmailMessage(EmailEnqueuedEvent event) {
        return emailRepository.findById(event.emailId())
                .orElseThrow(() -> new RuntimeException("Email not found: " + event.emailId()));
    }
}
