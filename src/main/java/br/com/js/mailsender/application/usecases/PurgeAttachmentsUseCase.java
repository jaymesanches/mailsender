package br.com.js.mailsender.application.usecases;

import br.com.js.mailsender.domain.model.EmailNotFoundException;
import br.com.js.mailsender.domain.ports.AttachmentStorageGateway;
import br.com.js.mailsender.domain.ports.EmailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PurgeAttachmentsUseCase {

    private final EmailRepository emailRepository;
    private final AttachmentStorageGateway storageGateway;

    /**
     * Storage primeiro, banco depois: se o delete falhar, o storagePath continua
     * apontando para bytes que ainda existem e a proxima rodada tenta de novo. Na
     * ordem inversa o ponteiro sumiria e os bytes ficariam orfaos para sempre.
     */
    public void execute(UUID emailId) {
        var emailMessage = emailRepository.findById(emailId)
                .orElseThrow(() -> new EmailNotFoundException(emailId));

        // antes de apagar qualquer byte: so expurga o que nunca mais sera enviado
        if (!emailMessage.isPurgeable()) {
            throw new IllegalStateException("Email " + emailId + " ainda pode ser enviado: "
                    + emailMessage.getStatus() + "/" + emailMessage.getAttempts());
        }

        for (var attachment : emailMessage.getAttachments()) {
            if (attachment.getStoragePath() != null) {
                storageGateway.delete(attachment.getStoragePath());
            }
        }

        emailMessage.markAttachmentsPurged();
        emailRepository.save(emailMessage);

        log.info("Anexos do email {} expurgados do storage", emailId);
    }
}
