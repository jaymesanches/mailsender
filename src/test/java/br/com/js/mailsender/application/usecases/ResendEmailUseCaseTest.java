package br.com.js.mailsender.application.usecases;

import br.com.js.mailsender.domain.model.Email;
import br.com.js.mailsender.domain.model.EmailMessage;
import br.com.js.mailsender.domain.model.EmailMessage.EmailStatus;
import br.com.js.mailsender.domain.model.EmailNotFoundException;
import br.com.js.mailsender.domain.ports.EmailDispatcher;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResendEmailUseCaseTest {

    @Mock
    private EmailRepository emailRepository;

    @Mock
    private EmailDispatcher emailDispatcher;

    @InjectMocks
    private ResendEmailUseCase useCase;

    private static EmailMessage comStatus(UUID id, EmailStatus status, int attempts) {
        return EmailMessage.reconstitute(id, Email.of("dest@example.com"), "assunto", "corpo", false,
                List.of(), status, Instant.now(), null, null, attempts, "erro anterior");
    }

    @Test
    void deveVoltarParaPendenteEEnfileirarNovamente() {
        var id = UUID.randomUUID();
        var message = comStatus(id, EmailStatus.FAILED, 1);
        when(emailRepository.findById(id)).thenReturn(Optional.of(message));
        when(emailRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = useCase.execute(id);

        assertThat(message.getStatus()).isEqualTo(EmailStatus.PENDING);
        assertThat(message.getAttempts()).isEqualTo(2);
        verify(emailRepository).save(message);
        verify(emailDispatcher).enqueue(id);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.status()).isEqualTo(EmailStatus.PENDING);
    }

    @Test
    void naoDeveReenviarEmailJaEnviado() {
        var id = UUID.randomUUID();
        when(emailRepository.findById(id)).thenReturn(Optional.of(comStatus(id, EmailStatus.SENT, 1)));

        assertThatThrownBy(() -> useCase.execute(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only failed emails can be retried");

        verify(emailRepository, never()).save(any());
        verifyNoInteractions(emailDispatcher);
    }

    @Test
    void naoDeveReenviarAoEsgotarOLimiteDeTentativas() {
        var id = UUID.randomUUID();
        when(emailRepository.findById(id))
                .thenReturn(Optional.of(comStatus(id, EmailStatus.FAILED, EmailMessage.MAX_ATTEMPTS)));

        assertThatThrownBy(() -> useCase.execute(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Retry limit reached");

        verify(emailRepository, never()).save(any());
        verifyNoInteractions(emailDispatcher);
    }

    @Test
    void deveFalharQuandoOEmailNaoExiste() {
        var id = UUID.randomUUID();
        when(emailRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(id))
                .isInstanceOf(EmailNotFoundException.class)
                .hasMessageContaining(id.toString());

        verifyNoInteractions(emailDispatcher);
    }
}
