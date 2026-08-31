package br.com.js.mailsender.domain.ports;

import br.com.js.mailsender.domain.model.EmailMessage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmailRepository {
    EmailMessage save(EmailMessage emailMessage);

    Optional<EmailMessage> findById(UUID id);

    /** Ids de e-mails em FALHA que ainda tem tentativa disponivel, mais antigos primeiro. */
    List<UUID> findRetriableIds(int limit);

    /** Ids de e-mails terminais, anteriores ao corte, que ainda tem anexo no storage. */
    List<UUID> findPurgeableIds(Instant antesDe, int limit);
}
