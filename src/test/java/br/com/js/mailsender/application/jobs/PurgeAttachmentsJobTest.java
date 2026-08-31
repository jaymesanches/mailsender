package br.com.js.mailsender.application.jobs;

import br.com.js.mailsender.application.usecases.PurgeAttachmentsUseCase;
import br.com.js.mailsender.domain.ports.EmailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurgeAttachmentsJobTest {

    @Mock
    private EmailRepository emailRepository;

    @Mock
    private PurgeAttachmentsUseCase purgeAttachmentsUseCase;

    @Captor
    private ArgumentCaptor<Instant> corteCaptor;

    private PurgeProperties properties;
    private PurgeAttachmentsJob job;

    @BeforeEach
    void setUp() {
        properties = new PurgeProperties();
        job = new PurgeAttachmentsJob(emailRepository, purgeAttachmentsUseCase, properties);
    }

    @Test
    void deveExpurgarCadaIdRetornado() {
        var primeiro = UUID.randomUUID();
        var segundo = UUID.randomUUID();
        when(emailRepository.findPurgeableIds(corteCaptor.capture(), anyInt()))
                .thenReturn(List.of(primeiro, segundo));

        job.expurgarAnexos();

        verify(purgeAttachmentsUseCase).execute(primeiro);
        verify(purgeAttachmentsUseCase).execute(segundo);
    }

    @Test
    void oCorteDeveRespeitarARetencaoConfigurada() {
        properties.setRetentionDays(30);
        when(emailRepository.findPurgeableIds(corteCaptor.capture(), anyInt())).thenReturn(List.of());

        job.expurgarAnexos();

        var esperado = Instant.now().minus(30, ChronoUnit.DAYS);
        assertThat(corteCaptor.getValue()).isCloseTo(esperado, within(5, ChronoUnit.SECONDS));
    }

    @Test
    void deveSeguirComOLoteQuandoUmIdFalha() {
        var problematico = UUID.randomUUID();
        var saudavel = UUID.randomUUID();
        when(emailRepository.findPurgeableIds(corteCaptor.capture(), anyInt()))
                .thenReturn(List.of(problematico, saudavel));
        doThrow(new IllegalStateException("ainda pode ser enviado"))
                .when(purgeAttachmentsUseCase).execute(problematico);

        job.expurgarAnexos();

        verify(purgeAttachmentsUseCase).execute(saudavel);
    }

    @Test
    void desligadoNaoDeveNemConsultarOBanco() {
        properties.setEnabled(false);

        job.expurgarAnexos();

        verifyNoInteractions(emailRepository, purgeAttachmentsUseCase);
    }

    private static org.assertj.core.data.TemporalUnitOffset within(long valor, ChronoUnit unidade) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(valor, unidade);
    }
}
