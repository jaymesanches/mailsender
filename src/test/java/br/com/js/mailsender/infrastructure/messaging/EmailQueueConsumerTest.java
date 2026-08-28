package br.com.js.mailsender.infrastructure.messaging;

import br.com.js.mailsender.domain.model.Email;
import br.com.js.mailsender.domain.model.EmailAttachment;
import br.com.js.mailsender.domain.model.EmailMessage;
import br.com.js.mailsender.domain.model.EmailMessage.EmailStatus;
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

    @InjectMocks
    private EmailQueueConsumer consumer;

    @Captor
    private ArgumentCaptor<EmailMessage> enviado;

    /** Como o adapter JPA devolve: anexo com storagePath e sem bytes. */
    private static EmailMessage pendenteComAnexo(UUID id) {
        return EmailMessage.reconstitute(id, Email.of("dest@example.com"), "assunto", "corpo", false,
                List.of(EmailAttachment.fromStorage("doc.txt", "text/plain", id + "/doc.txt")),
                EmailStatus.PENDING, Instant.now(), null);
    }

    @Test
    void deveRehidratarAnexosDoStorageAntesDeEnviar() {
        var id = UUID.randomUUID();
        var message = pendenteComAnexo(id);
        when(emailRepository.findById(id)).thenReturn(Optional.of(message));
        when(storageGateway.download(id + "/doc.txt")).thenReturn("conteudo".getBytes());

        consumer.consume(new EmailEnqueuedEvent(id));

        verify(emailGateway).send(enviado.capture());
        assertThat(enviado.getValue().getAttachments()).singleElement().satisfies(att -> {
            assertThat(att.getName()).isEqualTo("doc.txt");
            assertThat(att.getContent()).containsExactly("conteudo".getBytes());
        });

        verify(emailRepository).save(message);
        assertThat(message.getStatus()).isEqualTo(EmailStatus.SENT);
        assertThat(message.getSentAt()).isNotNull();
    }

    @Test
    void deveIgnorarMensagemQueJaSaiuDePendente() {
        var id = UUID.randomUUID();
        var jaEnviado = EmailMessage.reconstitute(id, Email.of("dest@example.com"), "assunto", "corpo", false,
                List.of(), EmailStatus.SENT, Instant.now(), Instant.now());
        when(emailRepository.findById(id)).thenReturn(Optional.of(jaEnviado));

        consumer.consume(new EmailEnqueuedEvent(id));

        verifyNoInteractions(emailGateway, storageGateway);
        verify(emailRepository, never()).save(any());
    }

    @Test
    void deveTraduzirFalhaDeEnvioEmAmqpExceptionMantendoPendente() {
        var id = UUID.randomUUID();
        var message = pendenteComAnexo(id);
        when(emailRepository.findById(id)).thenReturn(Optional.of(message));
        when(storageGateway.download(any())).thenReturn("conteudo".getBytes());
        doThrow(new RuntimeException("SMTP fora do ar")).when(emailGateway).send(any());

        var event = new EmailEnqueuedEvent(id);

        assertThatThrownBy(() -> consumer.consume(event))
                .isInstanceOf(AmqpException.class)
                .hasMessage("Failed to send email")
                .hasRootCauseMessage("SMTP fora do ar");

        // status intacto: quem decide o FAILED e a DLQ, nao o retry
        assertThat(message.getStatus()).isEqualTo(EmailStatus.PENDING);
        verify(emailRepository, never()).save(any());
    }

    @Test
    void deveFalharQuandoOEmailNaoExiste() {
        var id = UUID.randomUUID();
        when(emailRepository.findById(id)).thenReturn(Optional.empty());

        var event = new EmailEnqueuedEvent(id);

        assertThatThrownBy(() -> consumer.consume(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(id.toString());
        verifyNoInteractions(emailGateway, storageGateway);
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
    @EnumSource(value = EmailStatus.class, names = { "SENT", "FAILED" })
    void dlqDeveIgnorarMensagemQueJaSaiuDePendente(EmailStatus statusAtual) {
        var id = UUID.randomUUID();
        var jaProcessado = EmailMessage.reconstitute(id, Email.of("dest@example.com"), "assunto", "corpo", false,
                List.of(), statusAtual, Instant.now(), null);
        when(emailRepository.findById(id)).thenReturn(Optional.of(jaProcessado));

        consumer.consumeDlq(new EmailEnqueuedEvent(id));

        assertThat(jaProcessado.getStatus()).isEqualTo(statusAtual);
        verify(emailRepository, never()).save(any());
    }

    @Test
    void dlqDeveMarcarComoFalha() {
        var id = UUID.randomUUID();
        var message = pendenteComAnexo(id);
        when(emailRepository.findById(id)).thenReturn(Optional.of(message));

        consumer.consumeDlq(new EmailEnqueuedEvent(id));

        assertThat(message.getStatus()).isEqualTo(EmailStatus.FAILED);
        assertThat(message.getSentAt()).isNull();
        verify(emailRepository).save(message);
        verifyNoInteractions(emailGateway, storageGateway);
    }
}
