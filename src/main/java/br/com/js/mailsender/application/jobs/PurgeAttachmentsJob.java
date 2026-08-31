package br.com.js.mailsender.application.jobs;

import br.com.js.mailsender.application.usecases.PurgeAttachmentsUseCase;
import br.com.js.mailsender.domain.ports.EmailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class PurgeAttachmentsJob {

    private final EmailRepository emailRepository;
    private final PurgeAttachmentsUseCase purgeAttachmentsUseCase;
    private final PurgeProperties properties;

    @Scheduled(cron = "${mailsender.purge.cron:0 30 3 * * *}")
    public void expurgarAnexos() {
        if (!properties.isEnabled()) {
            log.info("Expurgo de anexos desligado (mailsender.purge.enabled=false)");
            return;
        }

        var corte = Instant.now().minus(properties.getRetentionDays(), ChronoUnit.DAYS);
        var ids = emailRepository.findPurgeableIds(corte, properties.getBatchSize());

        if (ids.isEmpty()) {
            return;
        }

        log.info("Expurgando anexos de {} e-mail(is) anteriores a {}", ids.size(), corte);

        int expurgados = 0;
        for (var id : ids) {
            // um id problematico nao pode abortar o lote
            try {
                purgeAttachmentsUseCase.execute(id);
                expurgados++;
            } catch (Exception e) {
                log.error("Falha ao expurgar anexos do email {}", id, e);
            }
        }

        log.info("Expurgo concluido: {} de {} e-mail(is)", expurgados, ids.size());
    }
}
