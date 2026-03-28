package br.com.js.mailsender.infrastructure.messaging;

import br.com.js.mailsender.domain.model.EmailAttachment;
import br.com.js.mailsender.domain.model.EmailMessage;
import br.com.js.mailsender.domain.ports.AttachmentStorageGateway;
import br.com.js.mailsender.domain.ports.EmailGateway;
import br.com.js.mailsender.domain.ports.EmailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailQueueConsumer {

    private final EmailRepository emailRepository;
    private final EmailGateway emailGateway;
    private final AttachmentStorageGateway storageGateway;

    @RabbitListener(queues = RabbitMQConfig.EMAIL_QUEUE)
    public void consume(EmailEnqueuedEvent event) {
        log.info("Consuming email sending task for ID: {}", event.emailId());

        EmailMessage emailMessage = emailRepository.findById(event.emailId())
                .orElseThrow(() -> new RuntimeException("Email not found: " + event.emailId()));

        if (emailMessage.getStatus() != EmailMessage.EmailStatus.PENDING) {
            log.warn("Email {} is already in status {}. Skipping.", event.emailId(), emailMessage.getStatus());
            return;
        }

        try {
            // Reconstruct attachments with bytes from MinIO
            List<EmailAttachment> attachmentsWithContent = emailMessage.getAttachments().stream()
                    .map(att -> {
                        byte[] content = storageGateway.download(att.getStoragePath());
                        return new EmailAttachment(att.getName(), att.getContentType(), content, att.getStoragePath());
                    })
                    .toList();

            // Create a temporary message with content for the gateway
            EmailMessage messageWithContent = EmailMessage.reconstitute(
                    emailMessage.getId(),
                    emailMessage.getTo(),
                    emailMessage.getSubject(),
                    emailMessage.getBody(),
                    emailMessage.isHtml(),
                    attachmentsWithContent,
                    emailMessage.getStatus(),
                    emailMessage.getCreatedAt(),
                    emailMessage.getSentAt()
            );

            emailGateway.send(messageWithContent);
            
            emailMessage.markAsSent();
            log.info("Email {} sent successfully", event.emailId());
        } catch (Exception e) {
            log.error("Failed to send email {}", event.emailId(), e);
            emailMessage.markAsFailed();
            // Aqui poderíamos lançar a exceção para o RabbitMQ tentar novamente (Retry Policy)
            // Mas para simplificar, estamos marcando como FAILED.
        }

        emailRepository.save(emailMessage);
    }
}
