package br.com.js.mailsender.application.usecases;

import br.com.js.mailsender.application.dtos.EmailResponse;
import br.com.js.mailsender.application.dtos.SendEmailRequest;
import br.com.js.mailsender.domain.model.Email;
import br.com.js.mailsender.domain.model.EmailAttachment;
import br.com.js.mailsender.domain.model.EmailMessage;
import br.com.js.mailsender.domain.ports.AttachmentStorageGateway;
import br.com.js.mailsender.domain.ports.EmailRepository;
import br.com.js.mailsender.infrastructure.messaging.EmailEnqueuedEvent;
import br.com.js.mailsender.infrastructure.messaging.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SendEmailUseCase {

    private final EmailRepository emailRepository;
    private final AttachmentStorageGateway storageGateway;
    private final RabbitTemplate rabbitTemplate;

    @Transactional
    public EmailResponse execute(SendEmailRequest request) {
        log.info("Enqueuing email request to: {}", request.to());

        var emailTo = Email.of(request.to());
        
        List<EmailAttachment> attachments = Collections.emptyList();
        if (request.attachments() != null && !request.attachments().isEmpty()) {
            attachments = request.attachments().stream()
                .map(file -> {
                    try {
                        return EmailAttachment.fromUpload(file.getOriginalFilename(), file.getContentType(), file.getBytes());
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to read attachment content", e);
                    }
                })
                .toList();
        }

        boolean isHtml = request.isHtml() != null ? request.isHtml() : false;
        var emailMessage = EmailMessage.create(emailTo, request.subject(), request.body(), isHtml, attachments);

        // Upload attachments and set storage paths
        for (EmailAttachment att : emailMessage.getAttachments()) {
            String storagePath = storageGateway.upload(emailMessage.getId(), att.getName(), att.getContent());
            att.setStoragePath(storagePath);
        }

        // 1. Persist the email in PENDING state
        emailRepository.save(emailMessage);

        // 2. Send to RabbitMQ
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EMAIL_EXCHANGE,
                RabbitMQConfig.EMAIL_ROUTING_KEY,
                new EmailEnqueuedEvent(emailMessage.getId())
        );

        return new EmailResponse(emailMessage.getId(), emailMessage.getStatus());
    }
}
