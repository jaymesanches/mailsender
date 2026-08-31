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
                status, Instant.now(), null, null, attempts, null);
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
        assertThat(message.getLastAccount()).isNull();
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

        message.markAsSent("conta-a");

        assertThat(message.getStatus()).isEqualTo(EmailStatus.SENT);
        assertThat(message.getSentAt()).isNotNull();
        assertThat(message.getLastAccount()).isEqualTo("conta-a");
    }

    @Test
    void marcarComoEnviadoLimpaOErroAnterior() {
        var message = comStatus(EmailStatus.FAILED, 1);
        message.markForRetry();

        message.markAsSent("conta-a");

        assertThat(message.getLastError()).isNull();
    }

    @Test
    void marcarComoFalhaGuardaOMotivoENaoDefineDataDeEnvio() {
        var message = nova();

        message.recordAttemptFailure("conta-b", "SMTP fora do ar");
        message.markAsFailed();

        assertThat(message.getStatus()).isEqualTo(EmailStatus.FAILED);
        assertThat(message.getLastError()).isEqualTo("SMTP fora do ar");
        assertThat(message.getLastAccount()).isEqualTo("conta-b");
        assertThat(message.getSentAt()).isNull();
    }

    @Test
    void deveTruncarErroMuitoLongoParaCaberNaColuna() {
        var message = nova();

        message.recordAttemptFailure("conta-a", "x".repeat(900));

        assertThat(message.getLastError()).hasSize(500);
    }

    @Test
    void marcarComoRejeitadoEhTerminal() {
        var message = nova();

        message.markAsRejected("550 usuario inexistente", "conta-b");

        assertThat(message.getStatus()).isEqualTo(EmailStatus.REJECTED);
        assertThat(message.getLastError()).isEqualTo("550 usuario inexistente");
        assertThat(message.getLastAccount()).isEqualTo("conta-b");
        assertThat(message.isRetriable()).isFalse();
        assertThatThrownBy(message::markForRetry)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only failed emails can be retried");
    }

    @Test
    void naoDeveReprocessarMensagemJaFinalizada() {
        var enviado = nova();
        enviado.markAsSent("conta-a");

        assertThatThrownBy(() -> enviado.markAsSent("conta-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Email already processed");
        assertThatThrownBy(enviado::markAsFailed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Email already processed");
        assertThatThrownBy(() -> enviado.markAsRejected("erro", "conta-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Email already processed");
        assertThatThrownBy(() -> enviado.recordAttemptFailure("conta-a", "erro"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Email already processed");
    }

    @Test
    void registrarTentativaGuardaDiagnosticoSemMudarStatus() {
        var message = nova();

        message.recordAttemptFailure("conta-b", "SMTP fora do ar");

        // e o que da diagnostico a quem so recebe o id depois, como a DLQ
        assertThat(message.getStatus()).isEqualTo(EmailStatus.PENDING);
        assertThat(message.getLastAccount()).isEqualTo("conta-b");
        assertThat(message.getLastError()).isEqualTo("SMTP fora do ar");
    }

    @Test
    void encerrarEmFalhaPreservaODiagnosticoDaTentativa() {
        var message = nova();
        message.recordAttemptFailure("conta-b", "SMTP fora do ar");

        message.markAsFailed();

        assertThat(message.getStatus()).isEqualTo(EmailStatus.FAILED);
        assertThat(message.getLastAccount()).isEqualTo("conta-b");
        assertThat(message.getLastError()).isEqualTo("SMTP fora do ar");
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

    @ParameterizedTest
    @EnumSource(value = EmailStatus.class, names = { "SENT", "REJECTED" })
    void terminalEhExpurgavel(EmailStatus status) {
        assertThat(comStatus(status, 1).isPurgeable()).isTrue();
    }

    @Test
    void pendenteNaoEhExpurgavel() {
        assertThat(nova().isPurgeable()).isFalse();
    }

    @Test
    void falhaReenviavelNaoEhExpurgavel() {
        // apagar o anexo aqui quebraria o reenvio, que nao re-sobe nada
        assertThat(comStatus(EmailStatus.FAILED, 1).isPurgeable()).isFalse();
    }

    @Test
    void falhaComTentativasEsgotadasEhExpurgavel() {
        assertThat(comStatus(EmailStatus.FAILED, EmailMessage.MAX_ATTEMPTS).isPurgeable()).isTrue();
    }

    @Test
    void expurgarAnexosZeraOsCaminhosDoStorage() {
        var message = EmailMessage.reconstitute(UUID.randomUUID(), TO, "assunto", "corpo", false,
                List.of(EmailAttachment.fromStorage("doc.txt", "text/plain", "chave/doc.txt")),
                EmailStatus.SENT, Instant.now(), Instant.now(), "conta-a", 1, null);

        message.markAttachmentsPurged();

        assertThat(message.getAttachments()).singleElement()
                .satisfies(att -> assertThat(att.getStoragePath()).isNull());
    }

    @ParameterizedTest
    @EnumSource(value = EmailStatus.class, names = { "PENDING" })
    void expurgoRecusaOQuePodeSerEnviado(EmailStatus status) {
        var pendente = comStatus(status, 1);
        assertThatThrownBy(pendente::markAttachmentsPurged)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ainda pode ser enviado");

        var reenviavel = comStatus(EmailStatus.FAILED, 1);
        assertThatThrownBy(reenviavel::markAttachmentsPurged)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ainda pode ser enviado");
    }

    @Test
    void reconstituteDevePreservarOEstadoPersistido() {
        var id = UUID.randomUUID();
        var criadoEm = Instant.parse("2026-01-01T10:00:00Z");
        var enviadoEm = Instant.parse("2026-01-01T10:00:05Z");

        var message = EmailMessage.reconstitute(id, TO, "assunto", "corpo", true,
                List.of(EmailAttachment.fromStorage("doc.txt", "text/plain", "chave/doc.txt")),
                EmailStatus.SENT, criadoEm, enviadoEm, "conta-a", 2, "erro anterior");

        assertThat(message.getId()).isEqualTo(id);
        assertThat(message.isHtml()).isTrue();
        assertThat(message.getStatus()).isEqualTo(EmailStatus.SENT);
        assertThat(message.getCreatedAt()).isEqualTo(criadoEm);
        assertThat(message.getSentAt()).isEqualTo(enviadoEm);
        assertThat(message.getAttempts()).isEqualTo(2);
        assertThat(message.getLastError()).isEqualTo("erro anterior");
        assertThat(message.getLastAccount()).isEqualTo("conta-a");
        assertThat(message.getAttachments()).singleElement()
                .satisfies(att -> {
                    assertThat(att.getStoragePath()).isEqualTo("chave/doc.txt");
                    assertThat(att.getContent()).isNull();
                });
    }
}
