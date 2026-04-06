package br.com.js.mailsender.infrastructure.messaging;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import br.com.js.mailsender.domain.model.EmailAttachment;
import br.com.js.mailsender.domain.model.EmailMessage;
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
        log.info("Consuming email sending task for ID: {}", event.emailId());

        var emailMessage = getEmailMessage(event);

        if (emailMessage.getStatus() != EmailMessage.EmailStatus.PENDING) {
            log.warn("Email {} is already in status {}. Skipping.", event.emailId(), emailMessage.getStatus());
            return;
        }

        try {
            // Reconstruct attachments with bytes from MinIO
            var attachmentsWithContent = emailMessage.getAttachments().stream()
                    .map(att -> {
                        byte[] content = storageGateway.download(att.getStoragePath());
                        return new EmailAttachment(att.getName(), att.getContentType(), content, att.getStoragePath());
                    })
                    .toList();

            // Create a temporary message with content for the gateway
            var messageWithContent = EmailMessage.reconstitute(
                    emailMessage.getId(),
                    emailMessage.getTo(),
                    emailMessage.getSubject(),
                    emailMessage.getBody(),
                    emailMessage.isHtml(),
                    attachmentsWithContent,
                    emailMessage.getStatus(),
                    emailMessage.getCreatedAt(),
                    emailMessage.getSentAt());

            emailGateway.send(messageWithContent);

            updateEmailStatus(emailMessage, EmailMessage.EmailStatus.SENT);
        } catch (Exception e) {
            log.error("Transient error sending email {}, delegating to RabbitMQ retry policy", event.emailId(), e);
            throw new org.springframework.amqp.AmqpException("Failed to send email", e);
        }
    }

    @RabbitListener(queues = RabbitMQConfig.DLQ_QUEUE)
    public void consumeDlq(EmailEnqueuedEvent event) {
        log.error("Email processing failed after all retries. Moving to FAILED status. ID: {}", event.emailId());
        var emailMessage = getEmailMessage(event);
        updateEmailStatus(emailMessage, EmailMessage.EmailStatus.FAILED);
    }

    @Transactional
    private void updateEmailStatus(EmailMessage emailMessage, EmailMessage.EmailStatus status) {
        switch (status) {
            case SENT:
                emailMessage.markAsSent();
                break;
            case FAILED:
                emailMessage.markAsFailed();
                break;
            default:
                break;
        }
        emailRepository.save(emailMessage);
    }

    @Transactional(readOnly = true)
    private EmailMessage getEmailMessage(EmailEnqueuedEvent event) {
        var emailMessage = emailRepository.findById(event.emailId())
                .orElseThrow(() -> new RuntimeException("Email not found: " + event.emailId()));
        return emailMessage;
    }
}
