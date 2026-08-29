package br.com.js.mailsender.application.usecases;

import br.com.js.mailsender.application.dtos.SendEmailRequest;
import br.com.js.mailsender.domain.model.EmailMessage;
import br.com.js.mailsender.domain.model.EmailMessage.EmailStatus;
import br.com.js.mailsender.domain.ports.AttachmentStorageGateway;
import br.com.js.mailsender.domain.ports.EmailDispatcher;
import br.com.js.mailsender.domain.ports.EmailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SendEmailUseCaseTest {

    @Mock
    private EmailRepository emailRepository;

    @Mock
    private AttachmentStorageGateway storageGateway;

    @Mock
    private EmailDispatcher emailDispatcher;

    @InjectMocks
    private SendEmailUseCase useCase;

    @Captor
    private ArgumentCaptor<EmailMessage> persistido;

    @Test
    void devePersistirPendenteEEnfileirarComOIdDaMensagem() {
        when(emailRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = useCase.execute(new SendEmailRequest("DEST@Example.com", "assunto", "corpo", null, null));

        verify(emailRepository).save(persistido.capture());
        var message = persistido.getValue();
        assertThat(message.getTo().value()).isEqualTo("dest@example.com");
        assertThat(message.getStatus()).isEqualTo(EmailStatus.PENDING);
        assertThat(message.isHtml()).isFalse();
        assertThat(message.getAttachments()).isEmpty();
        assertThat(message.getAttempts()).isEqualTo(1);

        verify(emailDispatcher).enqueue(message.getId());
        verifyNoInteractions(storageGateway);

        assertThat(response.id()).isEqualTo(message.getId());
        assertThat(response.status()).isEqualTo(EmailStatus.PENDING);
    }

    @Test
    void deveSubirAnexoAntesDePersistirEGuardarSomenteOCaminho() {
        when(emailRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storageGateway.upload(any(), eq("doc.txt"), any())).thenReturn("chave/doc.txt");

        var arquivo = new MockMultipartFile("attachments", "doc.txt", "text/plain", "conteudo".getBytes());

        useCase.execute(new SendEmailRequest("dest@example.com", "assunto", "corpo", true, List.of(arquivo)));

        verify(emailRepository).save(persistido.capture());
        var message = persistido.getValue();
        assertThat(message.isHtml()).isTrue();
        assertThat(message.getAttachments()).singleElement().satisfies(att -> {
            assertThat(att.getName()).isEqualTo("doc.txt");
            assertThat(att.getContentType()).isEqualTo("text/plain");
            assertThat(att.getStoragePath()).isEqualTo("chave/doc.txt");
        });

        // o anexo precisa existir no storage antes de a linha que o referencia ser gravada
        var ordem = inOrder(storageGateway, emailRepository, emailDispatcher);
        ordem.verify(storageGateway).upload(message.getId(), "doc.txt", "conteudo".getBytes());
        ordem.verify(emailRepository).save(any());
        ordem.verify(emailDispatcher).enqueue(message.getId());
    }

    @Test
    void naoDevePersistirNemEnfileirarQuandoDestinatarioInvalido() {
        var request = new SendEmailRequest("nao-e-email", "assunto", "corpo", false, null);

        assertThatThrownBy(() -> useCase.execute(request))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(emailRepository, storageGateway, emailDispatcher);
    }
}
