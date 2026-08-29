package br.com.js.mailsender.domain.model;

import br.com.js.mailsender.domain.model.EmailMessage.EmailStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailMessageTest {

    private static final Email TO = Email.of("dest@example.com");

    private static EmailMessage nova() {
        return EmailMessage.create(TO, "assunto", "corpo", false, List.of());
    }

    private static EmailMessage comStatus(EmailStatus status, int attempts) {
        return EmailMessage.reconstitute(UUID.randomUUID(), TO, "assunto", "corpo", false, List.of(),
                status, Instant.now(), null, attempts, null);
    }

    @Test
    void deveNascerPendenteComIdDataDeCriacaoEUmaTentativa() {
        var message = EmailMessage.create(TO, "assunto", "corpo", false, null);

        assertThat(message.getId()).isNotNull();
        assertThat(message.getStatus()).isEqualTo(EmailStatus.PENDING);
        assertThat(message.getCreatedAt()).isNotNull();
        assertThat(message.getSentAt()).isNull();
        assertThat(message.getAttachments()).isEmpty();
        assertThat(message.getAttempts()).isEqualTo(1);
        assertThat(message.getLastError()).isNull();
    }

    @Test
    void deveIsolarAListaDeAnexosRecebida() {
        var mutavel = new ArrayList<EmailAttachment>();
        mutavel.add(EmailAttachment.fromUpload("doc.txt", "text/plain", new byte[] { 1 }));

        var message = EmailMessage.create(TO, "assunto", "corpo", false, mutavel);
        mutavel.clear();

        assertThat(message.getAttachments()).hasSize(1);
        assertThatThrownBy(() -> message.getAttachments().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void marcarComoEnviadoDefineStatusEDataDeEnvio() {
        var message = nova();

        message.markAsSent();

        assertThat(message.getStatus()).isEqualTo(EmailStatus.SENT);
        assertThat(message.getSentAt()).isNotNull();
    }

    @Test
    void marcarComoEnviadoLimpaOErroAnterior() {
        var message = comStatus(EmailStatus.FAILED, 1);
        message.markForRetry();

        message.markAsSent();

        assertThat(message.getLastError()).isNull();
    }

    @Test
    void marcarComoFalhaGuardaOMotivoENaoDefineDataDeEnvio() {
        var message = nova();

        message.markAsFailed("SMTP fora do ar");

        assertThat(message.getStatus()).isEqualTo(EmailStatus.FAILED);
        assertThat(message.getLastError()).isEqualTo("SMTP fora do ar");
        assertThat(message.getSentAt()).isNull();
    }

    @Test
    void deveTruncarErroMuitoLongoParaCaberNaColuna() {
        var message = nova();

        message.markAsFailed("x".repeat(900));

        assertThat(message.getLastError()).hasSize(500);
    }

    @Test
    void marcarComoRejeitadoEhTerminal() {
        var message = nova();

        message.markAsRejected("550 usuario inexistente");

        assertThat(message.getStatus()).isEqualTo(EmailStatus.REJECTED);
        assertThat(message.getLastError()).isEqualTo("550 usuario inexistente");
        assertThat(message.isRetriable()).isFalse();
        assertThatThrownBy(message::markForRetry)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only failed emails can be retried");
    }

    @Test
    void naoDeveReprocessarMensagemJaFinalizada() {
        var enviado = nova();
        enviado.markAsSent();

        assertThatThrownBy(enviado::markAsSent)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Email already processed");
        assertThatThrownBy(() -> enviado.markAsFailed("erro"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Email already processed");
        assertThatThrownBy(() -> enviado.markAsRejected("erro"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Email already processed");
    }

    @Test
    void reenvioVoltaParaPendenteEIncrementaTentativa() {
        var message = comStatus(EmailStatus.FAILED, 1);

        message.markForRetry();

        assertThat(message.getStatus()).isEqualTo(EmailStatus.PENDING);
        assertThat(message.getAttempts()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(value = EmailStatus.class, names = { "PENDING", "SENT", "REJECTED" })
    void reenvioSoSaiDeFalha(EmailStatus statusAtual) {
        var message = comStatus(statusAtual, 1);

        assertThatThrownBy(message::markForRetry)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only failed emails can be retried");
    }

    @Test
    void reenvioParaAoEsgotarOLimiteDeTentativas() {
        var message = comStatus(EmailStatus.FAILED, EmailMessage.MAX_ATTEMPTS);

        assertThat(message.isRetriable()).isFalse();
        assertThatThrownBy(message::markForRetry)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Retry limit reached");
    }

    @Test
    void falhaComTentativaDisponivelEhReenviavel() {
        assertThat(comStatus(EmailStatus.FAILED, EmailMessage.MAX_ATTEMPTS - 1).isRetriable()).isTrue();
    }

    @Test
    void reconstituteDevePreservarOEstadoPersistido() {
        var id = UUID.randomUUID();
        var criadoEm = Instant.parse("2026-01-01T10:00:00Z");
        var enviadoEm = Instant.parse("2026-01-01T10:00:05Z");

        var message = EmailMessage.reconstitute(id, TO, "assunto", "corpo", true,
                List.of(EmailAttachment.fromStorage("doc.txt", "text/plain", "chave/doc.txt")),
                EmailStatus.SENT, criadoEm, enviadoEm, 2, "erro anterior");

        assertThat(message.getId()).isEqualTo(id);
        assertThat(message.isHtml()).isTrue();
        assertThat(message.getStatus()).isEqualTo(EmailStatus.SENT);
        assertThat(message.getCreatedAt()).isEqualTo(criadoEm);
        assertThat(message.getSentAt()).isEqualTo(enviadoEm);
        assertThat(message.getAttempts()).isEqualTo(2);
        assertThat(message.getLastError()).isEqualTo("erro anterior");
        assertThat(message.getAttachments()).singleElement()
                .satisfies(att -> {
                    assertThat(att.getStoragePath()).isEqualTo("chave/doc.txt");
                    assertThat(att.getContent()).isNull();
                });
    }
}
