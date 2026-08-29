package br.com.js.mailsender.application.jobs;

import br.com.js.mailsender.application.usecases.ResendEmailUseCase;
import br.com.js.mailsender.domain.ports.EmailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetryFailedEmailsJobTest {

    @Mock
    private EmailRepository emailRepository;

    @Mock
    private ResendEmailUseCase resendEmailUseCase;

    @InjectMocks
    private RetryFailedEmailsJob job;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(job, "batchSize", 50);
    }

    @Test
    void deveReenviarCadaIdRetornado() {
        var primeiro = UUID.randomUUID();
        var segundo = UUID.randomUUID();
        when(emailRepository.findRetriableIds(50)).thenReturn(List.of(primeiro, segundo));

        job.reenviarFalhas();

        verify(resendEmailUseCase).execute(primeiro);
        verify(resendEmailUseCase).execute(segundo);
    }

    @Test
    void deveSeguirComOLoteQuandoUmIdFalha() {
        var problematico = UUID.randomUUID();
        var saudavel = UUID.randomUUID();
        when(emailRepository.findRetriableIds(50)).thenReturn(List.of(problematico, saudavel));
        doThrow(new IllegalStateException("Retry limit reached")).when(resendEmailUseCase).execute(problematico);

        job.reenviarFalhas();

        // um id problematico nao pode abortar o lote
        verify(resendEmailUseCase).execute(saudavel);
    }

    @Test
    void naoDeveFazerNadaQuandoNaoHaFalhas() {
        when(emailRepository.findRetriableIds(50)).thenReturn(List.of());

        job.reenviarFalhas();

        verifyNoInteractions(resendEmailUseCase);
    }
}
