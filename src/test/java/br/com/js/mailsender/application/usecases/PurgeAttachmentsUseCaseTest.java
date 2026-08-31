package br.com.js.mailsender.application.usecases;

import br.com.js.mailsender.domain.model.Email;
import br.com.js.mailsender.domain.model.EmailAttachment;
import br.com.js.mailsender.domain.model.EmailMessage;
import br.com.js.mailsender.domain.model.EmailMessage.EmailStatus;
import br.com.js.mailsender.domain.model.EmailNotFoundException;
import br.com.js.mailsender.domain.ports.AttachmentStorageGateway;
import br.com.js.mailsender.domain.ports.EmailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurgeAttachmentsUseCaseTest {

    @Mock
    private EmailRepository emailRepository;

    @Mock
    private AttachmentStorageGateway storageGateway;

    @InjectMocks
    private PurgeAttachmentsUseCase useCase;

    private static EmailMessage comAnexos(UUID id, EmailStatus status, int attempts, String... caminhos) {
        var anexos = List.of(caminhos).stream()
                .map(caminho -> EmailAttachment.fromStorage("doc.txt", "text/plain", caminho))
                .toList();

        return EmailMessage.reconstitute(id, Email.of("dest@example.com"), "assunto", "corpo", false,
                anexos, status, Instant.now(), Instant.now(), "conta-a", attempts, null);
    }

    @Test
    void deveApagarDoStorageAntesDeGravarNoBanco() {
        var id = UUID.randomUUID();
        var message = comAnexos(id, EmailStatus.SENT, 1, "chave/a.txt", "chave/b.txt");
        when(emailRepository.findById(id)).thenReturn(Optional.of(message));

        useCase.execute(id);

        // a ordem e a garantia: banco antes deixaria bytes orfaos se o delete falhasse
        var ordem = inOrder(storageGateway, emailRepository);
        ordem.verify(storageGateway).delete("chave/a.txt");
        ordem.verify(storageGateway).delete("chave/b.txt");
        ordem.verify(emailRepository).save(message);

        assertThat(message.getAttachments()).allSatisfy(att -> assertThat(att.getStoragePath()).isNull());
    }

    @Test
    void naoDeveGravarNoBancoSeODeleteFalhar() {
        var id = UUID.randomUUID();
        var message = comAnexos(id, EmailStatus.SENT, 1, "chave/a.txt");
        when(emailRepository.findById(id)).thenReturn(Optional.of(message));
        doThrow(new RuntimeException("storage fora do ar")).when(storageGateway).delete(any());

        assertThatThrownBy(() -> useCase.execute(id)).isInstanceOf(RuntimeException.class);

        // o ponteiro sobrevive: a proxima rodada tenta de novo
        assertThat(message.getAttachments()).singleElement()
                .satisfies(att -> assertThat(att.getStoragePath()).isEqualTo("chave/a.txt"));
        verify(emailRepository, never()).save(any());
    }

    @Test
    void naoDeveExpurgarEmailAindaReenviavel() {
        var id = UUID.randomUUID();
        var message = comAnexos(id, EmailStatus.FAILED, 1, "chave/a.txt");
        when(emailRepository.findById(id)).thenReturn(Optional.of(message));

        assertThatThrownBy(() -> useCase.execute(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ainda pode ser enviado");

        // nenhum byte pode sair antes da checagem
        verifyNoInteractions(storageGateway);
        verify(emailRepository, never()).save(any());
    }

    @Test
    void naoDeveExpurgarEmailPendente() {
        var id = UUID.randomUUID();
        var message = comAnexos(id, EmailStatus.PENDING, 1, "chave/a.txt");
        when(emailRepository.findById(id)).thenReturn(Optional.of(message));

        assertThatThrownBy(() -> useCase.execute(id)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(storageGateway);
    }

    @Test
    void deveExpurgarFalhaComTentativasEsgotadas() {
        var id = UUID.randomUUID();
        var message = comAnexos(id, EmailStatus.FAILED, EmailMessage.MAX_ATTEMPTS, "chave/a.txt");
        when(emailRepository.findById(id)).thenReturn(Optional.of(message));

        useCase.execute(id);

        verify(storageGateway).delete("chave/a.txt");
        verify(emailRepository).save(message);
    }

    @Test
    void deveIgnorarAnexoJaExpurgado() {
        var id = UUID.randomUUID();
        var message = EmailMessage.reconstitute(id, Email.of("dest@example.com"), "assunto", "corpo", false,
                List.of(EmailAttachment.fromStorage("doc.txt", "text/plain", null)),
                EmailStatus.SENT, Instant.now(), Instant.now(), "conta-a", 1, null);
        when(emailRepository.findById(id)).thenReturn(Optional.of(message));

        useCase.execute(id);

        verifyNoInteractions(storageGateway);
        verify(emailRepository).save(message);
    }

    @Test
    void deveFalharQuandoOEmailNaoExiste() {
        var id = UUID.randomUUID();
        when(emailRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(id)).isInstanceOf(EmailNotFoundException.class);

        verifyNoInteractions(storageGateway);
    }
}
