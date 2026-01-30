package br.com.js.mailsender.domain.ports;

import br.com.js.mailsender.domain.model.EmailMessage;
import java.util.Optional;
import java.util.UUID;

public interface EmailRepository {
    EmailMessage save(EmailMessage emailMessage);

    Optional<EmailMessage> findById(UUID id);
}
