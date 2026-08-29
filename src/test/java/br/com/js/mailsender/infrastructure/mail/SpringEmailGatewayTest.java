package br.com.js.mailsender.infrastructure.mail;

import br.com.js.mailsender.domain.model.Email;
import br.com.js.mailsender.domain.model.EmailMessage;
import br.com.js.mailsender.domain.model.PermanentMailFailure;
import br.com.js.mailsender.domain.model.TransientMailFailure;
import jakarta.mail.Address;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpringEmailGatewayTest {

    @Mock
    private JavaMailSender javaMailSender;

    private SpringEmailGateway gateway;

    private static final EmailMessage MESSAGE = EmailMessage.create(
            Email.of("dest@example.com"), "assunto", "corpo", false, List.of());

    @BeforeEach
    void setUp() {
        gateway = new SpringEmailGateway(javaMailSender);
        when(javaMailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
    }

    @Test
    void enderecoRecusadoPeloServidorEhFalhaPermanente() throws Exception {
        Address[] invalidos = { new InternetAddress("naoexiste@example.com") };
        var recusa = new SendFailedException("550 no such user", null, null, null, invalidos);
        doThrow(new MailSendException(Map.<Object, Exception>of("msg", recusa)))
                .when(javaMailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> gateway.send(MESSAGE))
                .isInstanceOf(PermanentMailFailure.class)
                .hasMessageContaining("Recipient permanently rejected");
    }

    @Test
    void servidorInacessivelEhFalhaTransitoria() {
        doThrow(new MailSendException("Mail server connection failed",
                new RuntimeException("Connection refused")))
                .when(javaMailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> gateway.send(MESSAGE))
                .isInstanceOf(TransientMailFailure.class)
                .hasMessageContaining("Transient mail failure");
    }

    @Test
    void falhaSemEnderecoInvalidoEhTransitoria() throws Exception {
        // SendFailedException sem invalidAddresses: o servidor nao recusou o destinatario
        var falha = new SendFailedException("451 try again later", null, null,
                new Address[] { new InternetAddress("dest@example.com") }, null);
        doThrow(new MailSendException(Map.<Object, Exception>of("msg", falha)))
                .when(javaMailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> gateway.send(MESSAGE))
                .isInstanceOf(TransientMailFailure.class);
    }
}
