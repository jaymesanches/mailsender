package br.com.js.mailsender.application.usecases;

import br.com.js.mailsender.application.dtos.EmailResponse;
import br.com.js.mailsender.application.dtos.SendEmailRequest;
import br.com.js.mailsender.domain.model.Email;
import br.com.js.mailsender.domain.model.EmailMessage;
import br.com.js.mailsender.domain.ports.EmailGateway;
import br.com.js.mailsender.domain.ports.EmailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        var emailMessage = EmailMessage.create(emailTo, request.subject(), request.body());

        emailMessage = emailRepository.save(emailMessage);

        try {
            emailGateway.send(emailMessage);
            emailMessage.markAsSent();
        } catch (Exception e) {
            log.error("Failed to send email", e);
            emailMessage.markAsFailed();
        }

        emailRepository.save(emailMessage);

        return new EmailResponse(emailMessage.getId(), emailMessage.getStatus());
    }
}
