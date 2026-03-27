package br.com.js.mailsender.application.usecases;

import br.com.js.mailsender.application.dtos.EmailResponse;
import br.com.js.mailsender.application.dtos.SendEmailRequest;
import br.com.js.mailsender.domain.model.Email;
import br.com.js.mailsender.domain.model.EmailAttachment;
import br.com.js.mailsender.domain.model.EmailMessage;
import br.com.js.mailsender.domain.ports.EmailGateway;
import br.com.js.mailsender.domain.ports.EmailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Collections;

@Service
@RequiredArgsConstructor
@Slf4j
public class SendEmailUseCase {

    private final EmailRepository emailRepository;
    private final EmailGateway emailGateway;

    @Transactional
    public EmailResponse execute(SendEmailRequest request) {
        log.info("Processing email request to: {}", request.to());

        var emailTo = Email.of(request.to());
        
        List<EmailAttachment> attachments = Collections.emptyList();
        if (request.attachments() != null && !request.attachments().isEmpty()) {
            attachments = request.attachments().stream()
                .map(file -> {
                    try {
                        return new EmailAttachment(file.getOriginalFilename(), file.getContentType(), file.getBytes());
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to read attachment content", e);
                    }
                })
                .toList();
        }

        boolean isHtml = request.isHtml() != null ? request.isHtml() : false;
        
        var emailMessage = EmailMessage.create(emailTo, request.subject(), request.body(), isHtml, attachments);

        // Fazemos o primeiro save do status PENDING.
        // Diferente do que ocorria, não sobrescrevemos a var emailMessage com o retorno
        // pois como optamos por não salvar os bytes dos anexos no banco,
        // o retorno do save (reconstitute) voltaria sem os bytes em memória!
        emailRepository.save(emailMessage);

        try {
            // Utilizamos a intent original que AINDA possuí os binários dos anexos
            emailGateway.send(emailMessage);
            emailMessage.markAsSent();
        } catch (Exception e) {
            log.error("Failed to send email", e);
            emailMessage.markAsFailed();
        }

        // Atualizamos o status
        emailRepository.save(emailMessage);

        return new EmailResponse(emailMessage.getId(), emailMessage.getStatus());
    }
}
