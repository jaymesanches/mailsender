package br.com.js.mailsender.domain.model;

import br.com.js.mailsender.domain.model.EmailMessage.EmailStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailMessageTest {

    private static final Email TO = Email.of("dest@example.com");

    @Test
    void deveNascerPendenteComIdEDataDeCriacao() {
        var message = EmailMessage.create(TO, "assunto", "corpo", false, null);

        assertThat(message.getId()).isNotNull();
        assertThat(message.getStatus()).isEqualTo(EmailStatus.PENDING);
        assertThat(message.getCreatedAt()).isNotNull();
        assertThat(message.getSentAt()).isNull();
        assertThat(message.getAttachments()).isEmpty();
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
        var message = EmailMessage.create(TO, "assunto", "corpo", true, List.of());

        message.markAsSent();

        assertThat(message.getStatus()).isEqualTo(EmailStatus.SENT);
        assertThat(message.getSentAt()).isNotNull();
    }

    @Test
    void marcarComoFalhaNaoDefineDataDeEnvio() {
        var message = EmailMessage.create(TO, "assunto", "corpo", false, List.of());

        message.markAsFailed();

        assertThat(message.getStatus()).isEqualTo(EmailStatus.FAILED);
        assertThat(message.getSentAt()).isNull();
    }

    @Test
    void naoDeveReprocessarMensagemJaFinalizada() {
        var enviado = EmailMessage.create(TO, "assunto", "corpo", false, List.of());
        enviado.markAsSent();

        assertThatThrownBy(enviado::markAsSent)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Email already processed");
        assertThatThrownBy(enviado::markAsFailed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Email already processed");
    }

    @Test
    void reconstituteDevePreservarOEstadoPersistido() {
        var id = UUID.randomUUID();
        var criadoEm = Instant.parse("2026-01-01T10:00:00Z");
        var enviadoEm = Instant.parse("2026-01-01T10:00:05Z");

        var message = EmailMessage.reconstitute(id, TO, "assunto", "corpo", true,
                List.of(EmailAttachment.fromStorage("doc.txt", "text/plain", "chave/doc.txt")),
                EmailStatus.SENT, criadoEm, enviadoEm);

        assertThat(message.getId()).isEqualTo(id);
        assertThat(message.isHtml()).isTrue();
        assertThat(message.getStatus()).isEqualTo(EmailStatus.SENT);
        assertThat(message.getCreatedAt()).isEqualTo(criadoEm);
        assertThat(message.getSentAt()).isEqualTo(enviadoEm);
        assertThat(message.getAttachments()).singleElement()
                .satisfies(att -> {
                    assertThat(att.getStoragePath()).isEqualTo("chave/doc.txt");
                    assertThat(att.getContent()).isNull();
                });
    }
}
