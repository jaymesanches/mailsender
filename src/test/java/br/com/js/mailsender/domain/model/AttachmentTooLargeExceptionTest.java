package br.com.js.mailsender.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentTooLargeExceptionTest {

    private static final long MB = 1024L * 1024L;

    @Test
    void aMensagemDeveTrazerOsDoisTamanhosEmMegabytes() {
        var e = new AttachmentTooLargeException(20 * MB, 18 * MB);

        assertThat(e.getMessage()).contains("20.0 MB").contains("18.0 MB");
    }

    @Test
    void aMensagemNaoPodeDependerDoLocaleDaJvm() {
        var original = Locale.getDefault();
        try {
            // pt-BR formataria "20,0"; a resposta HTTP tem de ser igual em qualquer host
            Locale.setDefault(Locale.forLanguageTag("pt-BR"));

            var e = new AttachmentTooLargeException(20 * MB, 18 * MB);

            assertThat(e.getMessage()).contains("20.0 MB").doesNotContain("20,0");
        } finally {
            Locale.setDefault(original);
        }
    }
}
