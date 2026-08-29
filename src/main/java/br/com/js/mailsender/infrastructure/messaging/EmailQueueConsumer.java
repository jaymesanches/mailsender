package br.com.js.mailsender.infrastructure.messaging;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import br.com.js.mailsender.domain.model.EmailAttachment;
import br.com.js.mailsender.domain.model.EmailMessage;
import br.com.js.mailsender.domain.model.PermanentMailFailure;
import br.com.js.mailsender.domain.ports.AttachmentStorageGateway;
import br.com.js.mailsender.domain.ports.EmailGateway;
import br.com.js.mailsender.domain.ports.EmailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailQueueConsumer {

    private final EmailRepository emailRepository;
    private final EmailGateway emailGateway;
    private final AttachmentStorageGateway storageGateway;

    @RabbitListener(queues = RabbitMQConfig.EMAIL_QUEUE)
    public void consume(EmailEnqueuedEvent event) {
        log.info("Consuming email sending task for ID: {}", event.emailId());var emailMessage = getEmailMessage(event);

        if (emailMessage.getStatus() != EmailMessage.EmailStatus.PENDING) {
            log.warn("Email {} is already in status {}. Skipping.", event.emailId(), emailMessage.getStatus());
            return;
        }

        try {
            emailGateway.send(withAttachmentContent(emailMessage));

            emailMessage.markAsSent();
            emailRepository.save(emailMessage);
        } catch (PermanentMailFailure e) {
            // retentar nao muda o resultado: encerra em REJECTED sem passar pela DLQ
            log.error("Email {} rejeitado em definitivo: {}", event.emailId(), e.getMessage());
            emailMessage.markAsRejected(e.getMessage());
            emailRepository.save(emailMessage);
        } catch (Exception e) {
            log.error("Transient error sending email {}, delegating to RabbitMQ retry policy", event.emailId(), e);
            throw new AmqpException("Failed to send email", e);
        }
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
        emailMessage.markAsFailed("Falha no envio apos esgotar as tentativas");
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
                emailMessage.getAttempts(),
                emailMessage.getLastError());
    }

    /** Lanca de proposito quando nao acha: e o que aciona o retry do listener. */
    private EmailMessage getEmailMessage(EmailEnqueuedEvent event) {
        return emailRepository.findById(event.emailId())
                .orElseThrow(() -> new RuntimeException("Email not found: " + event.emailId()));
    }
}
