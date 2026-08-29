package br.com.js.mailsender.infrastructure.mail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class MailAccountPoolTest {

    @Mock
    private JavaMailSender autoconfigured;

    private final AtomicLong relogio = new AtomicLong(0);
    private final SendRateLimiter limiter = new InMemorySendRateLimiter(relogio::get);

    @Test
    void deveRepassarAuthEStartTlsParaOSender() {
        var props = new MailSenderProperties();
        var interno = new MailSenderProperties.Account();
        interno.setName("mailhog - interno");
        interno.setHost("10.117.2.204");
        interno.setPort(1025);
        interno.setAuth(false);
        interno.setStartTls(false);
        props.setAccounts(List.of(interno));

        var conta = new MailAccountPool(props, limiter, autoconfigured).acquire().orElseThrow();

        var javaMail = ((JavaMailSenderImpl) conta.sender()).getJavaMailProperties();
        assertThat(javaMail).containsEntry("mail.smtp.auth", "false")
                .containsEntry("mail.smtp.starttls.enable", "false");
    }

    @Test
    void oDefaultEhAuthEStartTlsLigadosPorCausaDoExchange() {
        var conta = new MailAccountPool(comContas(30, "conta-a"), limiter, autoconfigured)
                .acquire().orElseThrow();

        var javaMail = ((JavaMailSenderImpl) conta.sender()).getJavaMailProperties();
        assertThat(javaMail).containsEntry("mail.smtp.auth", "true")
                .containsEntry("mail.smtp.starttls.enable", "true");
    }

    private static MailSenderProperties comContas(int maxPerMinute, String... nomes) {
        var props = new MailSenderProperties();
        props.setAccounts(List.of(nomes).stream().map(nome -> {
            var conta = new MailSenderProperties.Account();
            conta.setName(nome);
            conta.setHost("smtp.example.com");
            conta.setMaxPerMinute(maxPerMinute);
            return conta;
        }).toList());
        return props;
    }

    @Test
    void semContasConfiguradasUsaOSenderAutoconfigurado() {
        var pool = new MailAccountPool(new MailSenderProperties(), limiter, autoconfigured);

        var conta = pool.acquire().orElseThrow();

        assertThat(conta.name()).isEqualTo("default");
        assertThat(conta.sender()).isSameAs(autoconfigured);
    }

    @Test
    void deveAlternarEntreAsContasParaEspalharACarga() {
        var pool = new MailAccountPool(comContas(10, "conta-a", "conta-b"), limiter, autoconfigured);

        var primeira = pool.acquire().orElseThrow().name();
        var segunda = pool.acquire().orElseThrow().name();

        assertThat(primeira).isNotEqualTo(segunda);
        assertThat(List.of(primeira, segunda)).containsExactlyInAnyOrder("conta-a", "conta-b");
    }

    @Test
    void deveCairNaOutraContaQuandoAPrimeiraEstaNoLimite() {
        var pool = new MailAccountPool(comContas(1, "conta-a", "conta-b"), limiter, autoconfigured);

        assertThat(pool.acquire()).isPresent();
        assertThat(pool.acquire()).isPresent();
    }

    @Test
    void deveDevolverVazioQuandoTodasAsContasEstaoNoLimite() {
        var pool = new MailAccountPool(comContas(1, "conta-a", "conta-b"), limiter, autoconfigured);

        pool.acquire();
        pool.acquire();

        // capacidade esgotada: o gateway traduz isso em ThrottledMailFailure
        assertThat(pool.acquire()).isEmpty();
    }

    @Test
    void deveVoltarAAtenderQuandoAJanelaDesliza() {
        var pool = new MailAccountPool(comContas(1, "conta-a"), limiter, autoconfigured);
        pool.acquire();
        assertThat(pool.acquire()).isEmpty();

        relogio.set(60_000);

        assertThat(pool.acquire()).isPresent();
    }
}
