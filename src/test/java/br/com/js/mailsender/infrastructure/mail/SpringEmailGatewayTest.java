package br.com.js.mailsender.infrastructure.mail;

import br.com.js.mailsender.domain.model.Email;
import br.com.js.mailsender.domain.model.EmailMessage;
import br.com.js.mailsender.domain.model.PermanentMailFailure;
import br.com.js.mailsender.domain.model.ThrottledMailFailure;
import br.com.js.mailsender.domain.model.TransientMailFailure;
import jakarta.mail.Address;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpringEmailGatewayTest {

    @Mock
    private JavaMailSender javaMailSender;

    @Mock
    private MailAccountPool accountPool;

    private SpringEmailGateway gateway;

    private static final EmailMessage MESSAGE = EmailMessage.create(
            Email.of("dest@example.com"), "assunto", "corpo", false, List.of());

    @BeforeEach
    void setUp() {
        gateway = new SpringEmailGateway(accountPool);
        lenient().when(accountPool.acquire())
                .thenReturn(Optional.of(new MailAccount("conta-a", javaMailSender, 30, "protocolo@osasco.sp.gov.br", null)));
        lenient().when(javaMailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
    }

    private void servidorResponde(Exception falha) {
        doThrow(new MailSendException(Map.<Object, Exception>of("msg", falha)))
                .when(javaMailSender).send(any(MimeMessage.class));
    }

    @Test
    void deveDefinirORemetenteDaConta() throws Exception {
        // sem isto o JavaMail deriva o remetente do usuario do SO e o Exchange
        // recusa com "SendAsDenied"
        var enviada = ArgumentCaptor.forClass(MimeMessage.class);

        gateway.send(MESSAGE);

        verify(javaMailSender).send(enviada.capture());
        assertThat(enviada.getValue().getFrom()).hasSize(1);
        assertThat(enviada.getValue().getFrom()[0].toString())
                .isEqualTo("protocolo@osasco.sp.gov.br");
    }

    @Test
    void deveIncluirONomeDeExibicaoQuandoConfigurado() throws Exception {
        when(accountPool.acquire()).thenReturn(Optional.of(new MailAccount(
                "conta-a", javaMailSender, 30, "protocolo@osasco.sp.gov.br", "Prefeitura de Osasco")));
        var enviada = ArgumentCaptor.forClass(MimeMessage.class);

        gateway.send(MESSAGE);

        verify(javaMailSender).send(enviada.capture());
        assertThat(enviada.getValue().getFrom()[0].toString())
                .contains("Prefeitura de Osasco")
                .contains("protocolo@osasco.sp.gov.br");
    }

    @Test
    void aFalhaCarregaAContaQueTentou() {
        servidorResponde(new SendFailedException("432 4.3.2 sender thread limit exceeded"));

        assertThatThrownBy(() -> gateway.send(MESSAGE))
                .isInstanceOf(ThrottledMailFailure.class)
                .extracting(e -> ((ThrottledMailFailure) e).account())
                .isEqualTo("conta-a");
    }

    @Test
    void throttleDoPoolNaoTemContaPorqueFalhaAntesDeEscolher() {
        when(accountPool.acquire()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gateway.send(MESSAGE))
                .isInstanceOf(ThrottledMailFailure.class)
                .extracting(e -> ((ThrottledMailFailure) e).account())
                .isNull();
    }

    @Test
    void deveDevolverONomeDaContaQueEnviou() {
        assertThat(gateway.send(MESSAGE)).isEqualTo("conta-a");
    }

    @Test
    void semContaDisponivelNoPoolEhThrottle() {
        when(accountPool.acquire()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gateway.send(MESSAGE))
                .isInstanceOf(ThrottledMailFailure.class)
                .hasMessageContaining("Nenhuma conta com capacidade");
    }

    @Test
    void enderecoRecusadoPeloServidorEhFalhaPermanente() throws Exception {
        Address[] invalidos = { new InternetAddress("naoexiste@example.com") };
        servidorResponde(new SendFailedException("550 5.1.1 no such user", null, null, null, invalidos));

        assertThatThrownBy(() -> gateway.send(MESSAGE))
                .isInstanceOf(PermanentMailFailure.class)
                .hasMessageContaining("Recipient permanently rejected");
    }

    @Test
    void throttlingDoExchangeEhFalhaDeThrottle() {
        // resposta real do Exchange Online ao estourar o limite por minuto
        servidorResponde(new SendFailedException(
                "432 4.3.2 STOREDRV.ClientSubmit; sender thread limit exceeded"));

        assertThatThrownBy(() -> gateway.send(MESSAGE))
                .isInstanceOf(ThrottledMailFailure.class)
                .hasMessageContaining("Server asked to slow down");
    }

    @Test
    void qualquer4xxPedeEspera() {
        servidorResponde(new SendFailedException("451 4.7.1 try again later"));

        assertThatThrownBy(() -> gateway.send(MESSAGE))
                .isInstanceOf(ThrottledMailFailure.class);
    }

    @Test
    void codigo5xxSemEnderecoInvalidoAindaEhPermanente() {
        servidorResponde(new SendFailedException("552 5.3.4 message too large"));

        assertThatThrownBy(() -> gateway.send(MESSAGE))
                .isInstanceOf(PermanentMailFailure.class)
                .hasMessageContaining("Server rejected the message");
    }

    @Test
    void servidorInacessivelSemCodigoEstendidoContinuaTransitorio() {
        doThrow(new MailSendException("Mail server connection failed",
                new RuntimeException("Connection refused")))
                .when(javaMailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> gateway.send(MESSAGE))
                .isInstanceOf(TransientMailFailure.class)
                .hasMessageContaining("Transient mail failure");
    }

    @Test
    void enderecoInvalidoTemPrecedenciaSobreOCodigoEstendido() throws Exception {
        // 4.x.x na mensagem, mas o destinatario foi recusado: permanente vence
        Address[] invalidos = { new InternetAddress("naoexiste@example.com") };
        servidorResponde(new SendFailedException("432 4.3.2 mixed signals", null, null, null, invalidos));

        assertThatThrownBy(() -> gateway.send(MESSAGE))
                .isInstanceOf(PermanentMailFailure.class);
    }
}
