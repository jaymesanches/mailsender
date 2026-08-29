package br.com.js.mailsender.application.usecases;

import br.com.js.mailsender.application.dtos.EmailResponse;
import br.com.js.mailsender.domain.model.EmailNotFoundException;
import br.com.js.mailsender.domain.ports.EmailDispatcher;
import br.com.js.mailsender.domain.ports.EmailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ResendEmailUseCase {

    private final EmailRepository emailRepository;
    private final EmailDispatcher emailDispatcher;

    /**
     * Os anexos nao sao re-enviados ao storage: o storagePath gravado continua
     * valido, porque nao existe rotina de limpeza do bucket.
     */
    public EmailResponse execute(UUID emailId) {
        var emailMessage = emailRepository.findById(emailId)
                .orElseThrow(() -> new EmailNotFoundException(emailId));

        emailMessage.markForRetry();
        var saved = emailRepository.save(emailMessage);

        emailDispatcher.enqueue(saved.getId());
        log.info("Email {} reenfileirado para reenvio (tentativa {})", emailId, saved.getAttempts());

        return new EmailResponse(saved.getId(), saved.getStatus());
    }
}
