package br.com.js.mailsender.infrastructure.mail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class MailAccountPoolTest {

    @Mock
    private JavaMailSender autoconfigured;

    private final AtomicLong relogio = new AtomicLong(0);
    private final SendRateLimiter limiter = new InMemorySendRateLimiter(relogio::get);

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void capturarLogs() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(MailAccountPool.class)).addAppender(logs);
    }

    @AfterEach
    void pararCaptura() {
        ((Logger) LoggerFactory.getLogger(MailAccountPool.class)).detachAppender(logs);
    }

    private boolean avisouSobreLimiteEmMemoria() {
        return logs.list.stream().anyMatch(evento -> evento.getLevel() == Level.WARN
                && evento.getFormattedMessage().contains("EM MEMORIA"));
    }

    @Test
    void deveAvisarNoBootQueOLimiteEmMemoriaValePorProcesso() {
        new MailAccountPool(comContas(30, "conta-a"), limiter, autoconfigured, "");

        assertThat(avisouSobreLimiteEmMemoria()).isTrue();
    }

    @Test
    void naoDeveAvisarQuandoNaoHaLimiteParaFurar() {
        // conta default de desenvolvimento nao tem teto: o aviso seria ruido
        new MailAccountPool(new MailSenderProperties(), limiter, autoconfigured, "");

        assertThat(avisouSobreLimiteEmMemoria()).isFalse();
    }

    @Test
    void naoDeveAvisarComLimiterDistribuido() {
        SendRateLimiter distribuido = (conta, max) -> true;

        new MailAccountPool(comContas(30, "conta-a"), distribuido, autoconfigured, "");

        // o aviso se desliga sozinho ao trocar a implementacao
        assertThat(avisouSobreLimiteEmMemoria()).isFalse();
    }

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

        var conta = new MailAccountPool(props, limiter, autoconfigured, "").acquire().orElseThrow();

        var javaMail = ((JavaMailSenderImpl) conta.sender()).getJavaMailProperties();
        assertThat(javaMail).containsEntry("mail.smtp.auth", "false")
                .containsEntry("mail.smtp.starttls.enable", "false");
    }

    @Test
    void oDefaultEhAuthEStartTlsLigadosPorCausaDoExchange() {
        var conta = new MailAccountPool(comContas(30, "conta-a"), limiter, autoconfigured, "")
                .acquire().orElseThrow();

        var javaMail = ((JavaMailSenderImpl) conta.sender()).getJavaMailProperties();
        assertThat(javaMail).containsEntry("mail.smtp.auth", "true")
                .containsEntry("mail.smtp.starttls.enable", "true");
    }

    @Test
    void deveAplicarTimeoutsPadraoParaNaoPendurarAThreadDoConsumidor() {
        var conta = new MailAccountPool(comContas(30, "conta-a"), limiter, autoconfigured, "")
                .acquire().orElseThrow();

        var javaMail = ((JavaMailSenderImpl) conta.sender()).getJavaMailProperties();
        assertThat(javaMail).containsEntry("mail.smtp.connectiontimeout", "5000")
                .containsEntry("mail.smtp.timeout", "10000")
                .containsEntry("mail.smtp.writetimeout", "10000");
    }

    @Test
    void propriedadesDaContaSobrescrevemOsPadroes() {
        var props = new MailSenderProperties();
        var conta = new MailSenderProperties.Account();
        conta.setName("conta-a");
        conta.setHost("smtp.example.com");
        conta.setProperties(Map.of(
                "mail.smtp.timeout", "30000",
                "mail.smtp.ssl.trust", "smtp.example.com"));
        props.setAccounts(List.of(conta));

        var escolhida = new MailAccountPool(props, limiter, autoconfigured, "").acquire().orElseThrow();

        var javaMail = ((JavaMailSenderImpl) escolhida.sender()).getJavaMailProperties();
        assertThat(javaMail).containsEntry("mail.smtp.timeout", "30000")
                .containsEntry("mail.smtp.ssl.trust", "smtp.example.com")
                // o que nao foi sobrescrito continua no padrao
                .containsEntry("mail.smtp.connectiontimeout", "5000");
    }

    @Test
    void chaveComPontoPrecisaDeColchetesNoYaml() {
        // o binder do Spring trata ponto como aninhamento em Map<String, String>;
        // sem colchetes a propriedade nao chega na conta
        var source = new MapConfigurationPropertySource(Map.of(
                "mailsender.accounts[0].name", "conta-a",
                "mailsender.accounts[0].host", "smtp.example.com",
                "mailsender.accounts[0].properties[mail.smtp.timeout]", "7000"));

        var props = new Binder(source).bind("mailsender", MailSenderProperties.class).get();

        assertThat(props.getAccounts()).singleElement()
                .satisfies(conta -> assertThat(conta.getProperties())
                        .containsEntry("mail.smtp.timeout", "7000"));
    }

    private static MailSenderProperties.Account contaCom(String nome, String username,
            String from, String fromName) {
        var conta = new MailSenderProperties.Account();
        conta.setName(nome);
        conta.setHost("smtp.example.com");
        conta.setUsername(username);
        conta.setFrom(from);
        conta.setFromName(fromName);
        return conta;
    }

    private static MailAccountPool poolCom(MailSenderProperties.Account conta,
            SendRateLimiter limiter, JavaMailSender autoconfigured) {
        var props = new MailSenderProperties();
        props.setAccounts(List.of(conta));
        return new MailAccountPool(props, limiter, autoconfigured, "");
    }

    @Test
    void remetenteVazioCaiNoUsername() {
        // no Exchange a caixa autenticada so pode enviar como ela mesma
        var conta = poolCom(contaCom("pmo", "protocolo@osasco.sp.gov.br", null, null),
                limiter, autoconfigured).acquire().orElseThrow();

        assertThat(conta.from()).isEqualTo("protocolo@osasco.sp.gov.br");
    }

    @Test
    void remetenteExplicitoVenceOUsername() {
        var conta = poolCom(contaCom("pmo", "protocolo@osasco.sp.gov.br",
                "naoresponda@osasco.sp.gov.br", "Prefeitura"), limiter, autoconfigured)
                .acquire().orElseThrow();

        assertThat(conta.from()).isEqualTo("naoresponda@osasco.sp.gov.br");
        assertThat(conta.fromName()).isEqualTo("Prefeitura");
    }

    @Test
    void semContaConfiguradaOFromVemDoSpringMail() {
        var pool = new MailAccountPool(new MailSenderProperties(), limiter, autoconfigured,
                "protocolo@mailhog.com");

        assertThat(pool.acquire().orElseThrow().from()).isEqualTo("protocolo@mailhog.com");
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
        var pool = new MailAccountPool(new MailSenderProperties(), limiter, autoconfigured, "");

        var conta = pool.acquire().orElseThrow();

        assertThat(conta.name()).isEqualTo("default");
        assertThat(conta.sender()).isSameAs(autoconfigured);
    }

    @Test
    void deveAlternarEntreAsContasParaEspalharACarga() {
        var pool = new MailAccountPool(comContas(10, "conta-a", "conta-b"), limiter, autoconfigured, "");

        var primeira = pool.acquire().orElseThrow().name();
        var segunda = pool.acquire().orElseThrow().name();

        assertThat(primeira).isNotEqualTo(segunda);
        assertThat(List.of(primeira, segunda)).containsExactlyInAnyOrder("conta-a", "conta-b");
    }

    @Test
    void deveCairNaOutraContaQuandoAPrimeiraEstaNoLimite() {
        var pool = new MailAccountPool(comContas(1, "conta-a", "conta-b"), limiter, autoconfigured, "");

        assertThat(pool.acquire()).isPresent();
        assertThat(pool.acquire()).isPresent();
    }

    @Test
    void deveDevolverVazioQuandoTodasAsContasEstaoNoLimite() {
        var pool = new MailAccountPool(comContas(1, "conta-a", "conta-b"), limiter, autoconfigured, "");

        pool.acquire();
        pool.acquire();

        // capacidade esgotada: o gateway traduz isso em ThrottledMailFailure
        assertThat(pool.acquire()).isEmpty();
    }

    @Test
    void deveVoltarAAtenderQuandoAJanelaDesliza() {
        var pool = new MailAccountPool(comContas(1, "conta-a"), limiter, autoconfigured, "");
        pool.acquire();
        assertThat(pool.acquire()).isEmpty();

        relogio.set(60_000);

        assertThat(pool.acquire()).isPresent();
    }
}
