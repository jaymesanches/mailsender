package br.com.js.mailsender.application.jobs;

import br.com.js.mailsender.application.usecases.ResendEmailUseCase;
import br.com.js.mailsender.domain.ports.EmailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RetryFailedEmailsJob {

    private final EmailRepository emailRepository;
    private final ResendEmailUseCase resendEmailUseCase;

    @Value("${mailsender.retry.batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${mailsender.retry.interval:60000}")
    public void reenviarFalhas() {
        var ids = emailRepository.findRetriableIds(batchSize);

        if (ids.isEmpty()) {
            return;
        }

        log.info("Reenviando {} e-mail(is) em falha", ids.size());

        for (var id : ids) {
            // um id problematico nao pode abortar o lote
            try {
                resendEmailUseCase.execute(id);
            } catch (Exception e) {
                log.error("Falha ao reenfileirar o email {}", id, e);
            }
        }
    }
}
