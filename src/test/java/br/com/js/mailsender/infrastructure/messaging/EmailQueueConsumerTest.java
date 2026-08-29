package br.com.js.mailsender.infrastructure.messaging;

import br.com.js.mailsender.domain.model.Email;
import br.com.js.mailsender.domain.model.EmailAttachment;
import br.com.js.mailsender.domain.model.EmailMessage;
import br.com.js.mailsender.domain.model.EmailMessage.EmailStatus;
import br.com.js.mailsender.domain.model.PermanentMailFailure;
import br.com.js.mailsender.domain.model.ThrottledMailFailure;
import br.com.js.mailsender.domain.model.TransientMailFailure;
import br.com.js.mailsender.domain.ports.AttachmentStorageGateway;
import br.com.js.mailsender.domain.ports.EmailGateway;
import br.com.js.mailsender.domain.ports.EmailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailQueueConsumerTest {

    @Mock
    private EmailRepository emailRepository;

    @Mock
    private EmailGateway emailGateway;

    @Mock
    private AttachmentStorageGateway storageGateway;

    @Mock
    private RabbitEmailDispatcher dispatcher;

    @InjectMocks
    private EmailQueueConsumer consumer;

    @Captor
    private ArgumentCaptor<EmailMessage> enviado;

    /** Como o adapter JPA devolve: anexo com storagePath e sem bytes. */
    private static EmailMessage pendenteComAnexo(UUID id) {
        return EmailMessage.reconstitute(id, Email.of("dest@example.com"), "assunto", "corpo", false,
                List.of(EmailAttachment.fromStorage("doc.txt", "text/plain", id + "/doc.txt")),
                EmailStatus.PENDING, Instant.now(), null, null, 1, null);
    }

    private static EmailMessage comStatus(UUID id, EmailStatus status) {
        return EmailMessage.reconstitute(id, Email.of("dest@example.com"), "assunto", "corpo", false,
                List.of(), status, Instant.now(), null, null, 1, null);
    }

    @Test
    void deveRehidratarAnexosDoStorageAntesDeEnviar() {
        var id = UUID.randomUUID();
        var message = pendenteComAnexo(id);
        when(emailRepository.findById(id)).thenReturn(Optional.of(message));
        when(storageGateway.download(id + "/doc.txt")).thenReturn("conteudo".getBytes());
        when(emailGateway.send(any())).thenReturn("conta-a");

        consumer.consume(new EmailEnqueuedEvent(id), null);

        verify(emailGateway).send(enviado.capture());
        assertThat(enviado.getValue().getAttachments()).singleElement().satisfies(att -> {
            assertThat(att.getName()).isEqualTo("doc.txt");
            assertThat(att.getContent()).containsExactly("conteudo".getBytes());
        });

        verify(emailRepository).save(message);
        assertThat(message.getStatus()).isEqualTo(EmailStatus.SENT);
        assertThat(message.getSentAt()).isNotNull();
        assertThat(message.getLastAccount()).isEqualTo("conta-a");
    }

    @ParameterizedTest
    @EnumSource(value = EmailStatus.class, names = { "SENT", "FAILED", "REJECTED" })
    void deveIgnorarMensagemQueJaSaiuDePendente(EmailStatus statusAtual) {
        var id = UUID.randomUUID();
        var message = comStatus(id, statusAtual);
        when(emailRepository.findById(id)).thenReturn(Optional.of(message));

        consumer.consume(new EmailEnqueuedEvent(id), null);

        verifyNoInteractions(emailGateway, storageGateway, dispatcher);
        verify(emailRepository, never()).save(any());
    }

    @Test
    void falhaTransitoriaViraAmqpExceptionMantendoPendente() {
        var id = UUID.randomUUID();
        var message = pendenteComAnexo(id);
        when(emailRepository.findById(id)).thenReturn(Optional.of(message));
        when(storageGateway.download(any())).thenReturn("conteudo".getBytes());
        doThrow(new TransientMailFailure("SMTP fora do ar", "conta-b", new RuntimeException()))
                .when(emailGateway).send(any());

        var event = new EmailEnqueuedEvent(id);

        assertThatThrownBy(() -> consumer.consume(event, null))
                .isInstanceOf(AmqpException.class)
                .hasMessage("Failed to send email");

        // status intacto: quem decide o FAILED e a DLQ, nao o retry
        assertThat(message.getStatus()).isEqualTo(EmailStatus.PENDING);
        // mas o diagnostico fica gravado: a DLQ so recebe o id
        assertThat(message.getLastAccount()).isEqualTo("conta-b");
        assertThat(message.getLastError()).isEqualTo("SMTP fora do ar");
        verify(emailRepository).save(message);
    }

    @Test
    void falhaPermanenteViraRejectedSemPassarPelaDlq() {
        var id = UUID.randomUUID();
        var message = pendenteComAnexo(id);
        when(emailRepository.findById(id)).thenReturn(Optional.of(message));
        when(storageGateway.download(any())).thenReturn("conteudo".getBytes());
        doThrow(new PermanentMailFailure("550 usuario inexistente", "conta-b", new RuntimeException()))
                .when(emailGateway).send(any());

        // nao lanca: retentar uma caixa que nao existe seria desperdicio
        consumer.consume(new EmailEnqueuedEvent(id), null);

        assertThat(message.getStatus()).isEqualTo(EmailStatus.REJECTED);
        assertThat(message.getLastError()).isEqualTo("550 usuario inexistente");
        assertThat(message.getLastAccount()).isEqualTo("conta-b");
        verify(emailRepository).save(message);
        verifyNoInteractions(dispatcher);
    }

    @Test
    void throttlingVaiParaAEsperaSemMexerEmStatusNemTentativas() {
        var id = UUID.randomUUID();
        var message = pendenteComAnexo(id);
        when(emailRepository.findById(id)).thenReturn(Optional.of(message));
        when(storageGateway.download(any())).thenReturn("conteudo".getBytes());
        doThrow(new ThrottledMailFailure("432 4.3.2 sender thread limit exceeded", "conta-b", new RuntimeException()))
                .when(emailGateway).send(any());

        // sem header: primeiro ciclo
        consumer.consume(new EmailEnqueuedEvent(id), null);

        verify(dispatcher).enqueueAfterWait(id, 1);
        assertThat(message.getStatus()).isEqualTo(EmailStatus.PENDING);
        assertThat(message.getAttempts()).isEqualTo(1);
        verify(emailRepository, never()).save(any());
    }

    @Test
    void cadaVoltaDaEsperaIncrementaOCiclo() {
        var id = UUID.randomUUID();
        var message = pendenteComAnexo(id);
        when(emailRepository.findById(id)).thenReturn(Optional.of(message));
        when(storageGateway.download(any())).thenReturn("conteudo".getBytes());
        doThrow(new ThrottledMailFailure("432 4.3.2", "conta-b", new RuntimeException()))
                .when(emailGateway).send(any());

        consumer.consume(new EmailEnqueuedEvent(id), 4);

        verify(dispatcher).enqueueAfterWait(id, 5);
    }

    @Test
    void throttlingPersistenteAcabaVirandoFalhaRegistrada() {
        var id = UUID.randomUUID();
        var message = pendenteComAnexo(id);
        when(emailRepository.findById(id)).thenReturn(Optional.of(message));
        when(storageGateway.download(any())).thenReturn("conteudo".getBytes());
        doThrow(new ThrottledMailFailure("432 4.3.2", "conta-b", new RuntimeException()))
                .when(emailGateway).send(any());

        consumer.consume(new EmailEnqueuedEvent(id), EmailQueueConsumer.MAX_THROTTLE_CYCLES);

        // nao volta para a espera: registra e entra no fluxo de reenvio
        verifyNoInteractions(dispatcher);
        assertThat(message.getStatus()).isEqualTo(EmailStatus.FAILED);
        assertThat(message.getLastError()).contains("Throttling persistente");
        assertThat(message.getLastAccount()).isEqualTo("conta-b");
        assertThat(message.isRetriable()).isTrue();
        verify(emailRepository).save(message);
    }

    @Test
    void deveFalharQuandoOEmailNaoExiste() {
        var id = UUID.randomUUID();
        when(emailRepository.findById(id)).thenReturn(Optional.empty());

        var event = new EmailEnqueuedEvent(id);

        assertThatThrownBy(() -> consumer.consume(event, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(id.toString());
        verifyNoInteractions(emailGateway, storageGateway, dispatcher);
    }

    @Test
    void dlqDeveDescartarMensagemDeEmailInexistente() {
        var id = UUID.randomUUID();
        when(emailRepository.findById(id)).thenReturn(Optional.empty());

        // nao pode propagar: sem fila atras da DLQ, viraria poison message
        consumer.consumeDlq(new EmailEnqueuedEvent(id));

        verify(emailRepository, never()).save(any());
        verifyNoInteractions(emailGateway, storageGateway);
    }

    @ParameterizedTest
    @EnumSource(value = EmailStatus.class, names = { "SENT", "FAILED", "REJECTED" })
    void dlqDeveIgnorarMensagemQueJaSaiuDePendente(EmailStatus statusAtual) {
        var id = UUID.randomUUID();
        var message = comStatus(id, statusAtual);
        when(emailRepository.findById(id)).thenReturn(Optional.of(message));

        consumer.consumeDlq(new EmailEnqueuedEvent(id));

        assertThat(message.getStatus()).isEqualTo(statusAtual);
        verify(emailRepository, never()).save(any());
    }

    @Test
    void dlqDevePreservarODiagnosticoGravadoPelasTentativas() {
        var id = UUID.randomUUID();
        var message = pendenteComAnexo(id);
        message.recordAttemptFailure("conta-b", "SMTP fora do ar");
        when(emailRepository.findById(id)).thenReturn(Optional.of(message));

        consumer.consumeDlq(new EmailEnqueuedEvent(id));

        assertThat(message.getStatus()).isEqualTo(EmailStatus.FAILED);
        // a conta e o erro reais vem da tentativa, nao de uma mensagem generica
        assertThat(message.getLastAccount()).isEqualTo("conta-b");
        assertThat(message.getLastError()).isEqualTo("SMTP fora do ar");
        assertThat(message.getSentAt()).isNull();
        assertThat(message.isRetriable()).isTrue();
        verify(emailRepository).save(message);
        verifyNoInteractions(emailGateway, storageGateway);
    }

    @Test
    void parkingApenasLogaSemTocarNoBanco() {
        // nao se sabe se o e-mail saiu: alterar status poderia reenviar algo entregue
        consumer.consumeParking(new EmailEnqueuedEvent(UUID.randomUUID()));

        verifyNoInteractions(emailRepository, emailGateway, storageGateway, dispatcher);
    }
}
