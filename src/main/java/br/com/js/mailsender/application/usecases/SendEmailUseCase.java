package br.com.js.mailsender.application.usecases;

import br.com.js.mailsender.application.dtos.EmailResponse;
import br.com.js.mailsender.application.dtos.SendEmailRequest;
import br.com.js.mailsender.domain.model.Email;
import br.com.js.mailsender.domain.model.EmailAttachment;
import br.com.js.mailsender.domain.model.EmailMessage;
import br.com.js.mailsender.domain.ports.AttachmentStorageGateway;
import br.com.js.mailsender.domain.ports.EmailDispatcher;
import br.com.js.mailsender.domain.ports.EmailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SendEmailUseCase {

    private final EmailRepository emailRepository;
    private final AttachmentStorageGateway storageGateway;
    private final EmailDispatcher emailDispatcher;

    public EmailResponse execute(SendEmailRequest request) {
        log.info("Enqueuing email request to: {}", request.to());

        var emailTo = Email.of(request.to());

        List<EmailAttachment> attachments = Collections.emptyList();
        if (request.attachments() != null && !request.attachments().isEmpty()) {
            attachments = request.attachments().stream()
                    .map(file -> {
                        try {
                            return EmailAttachment.fromUpload(file.getOriginalFilename(), file.getContentType(),
                                    file.getBytes());
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to read attachment content", e);
                        }
                    })
                    .toList();
        }

        var isHtml = request.isHtml() != null ? request.isHtml() : false;
        var emailMessage = EmailMessage.create(emailTo, request.subject(), request.body(), isHtml, attachments);

        // o anexo tem de existir no storage antes da linha que o referencia
        for (EmailAttachment att : emailMessage.getAttachments()) {
            var storagePath = storageGateway.upload(emailMessage.getId(), att.getName(), att.getContent());
            att.setStoragePath(storagePath);
        }

        var savedEmail = emailRepository.save(emailMessage);

        emailDispatcher.enqueue(savedEmail.getId());

        return new EmailResponse(savedEmail.getId(), savedEmail.getStatus());
    }
}
