package br.com.js.mailsender.infrastructure.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Contas sao intercambiaveis: existem para somar capacidade. Entrega a primeira
 * com permit livre, comecando de um indice rotativo para espalhar a carga.
 */
@Slf4j
@Component
public class MailAccountPool {

    /**
     * Sem timeout o JavaMail espera para sempre: um host que aceita a conexao TCP e
     * nao responde prende a thread do consumidor indefinidamente — nada estoura, nem
     * o retry nem a fila de espera entram em acao, e a vazao simplesmente para.
     */
    private static final Map<String, String> TIMEOUTS_PADRAO = Map.of(
            "mail.smtp.connectiontimeout", "5000",
            "mail.smtp.timeout", "10000",
            "mail.smtp.writetimeout", "10000");

    private final List<MailAccount> accounts;
    private final SendRateLimiter rateLimiter;
    private final AtomicInteger proxima = new AtomicInteger();

    public MailAccountPool(MailSenderProperties properties, SendRateLimiter rateLimiter,
            JavaMailSender autoconfigured) {
        this.rateLimiter = rateLimiter;
        this.accounts = properties.getAccounts().isEmpty()
                ? List.of(new MailAccount("default", autoconfigured, Integer.MAX_VALUE))
                : properties.getAccounts().stream().map(MailAccountPool::toAccount).toList();

        log.info("Pool de envio com {} conta(s): {}", accounts.size(), accounts.stream().map(MailAccount::name).toList());
        avisarSobreLimiteEmMemoria(rateLimiter);
    }

    /**
     * O limite em memoria vale por processo: com duas instancias o total enviado vira
     * o dobro do limite do provedor, e a falha e silenciosa — aparece la na frente
     * como throttling constante e dificil de atribuir. A aplicacao nao tem como saber
     * quantas instancias existem, entao avisa no boot. So avisa quando ha limite real
     * para furar: a conta `default` de desenvolvimento nao tem teto.
     */
    private void avisarSobreLimiteEmMemoria(SendRateLimiter rateLimiter) {
        boolean temLimiteReal = accounts.stream().anyMatch(conta -> conta.maxPerMinute() < Integer.MAX_VALUE);

        if (rateLimiter instanceof InMemorySendRateLimiter && temLimiteReal) {
            log.warn("Controle de taxa EM MEMORIA: os limites por minuto sao contados por processo. "
                    + "Ao rodar mais de uma instancia, troque o SendRateLimiter por uma implementacao "
                    + "distribuida(Ex. Redis) ou particione as contas por instancia, senao o limite do provedor "
                    + "sera furado.");
        }
    }

    public Optional<MailAccount> acquire() {
        int inicio = proxima.getAndIncrement();

        for (int i = 0; i < accounts.size(); i++) {
            var account = accounts.get(Math.floorMod(inicio + i, accounts.size()));
            if (rateLimiter.tryAcquire(account.name(), account.maxPerMinute())) {
                return Optional.of(account);
            }
        }

        return Optional.empty();
    }

    private static MailAccount toAccount(MailSenderProperties.Account config) {
        var sender = new JavaMailSenderImpl();
        sender.setHost(config.getHost());
        sender.setPort(config.getPort());
        sender.setUsername(config.getUsername());
        sender.setPassword(config.getPassword());

        Properties props = sender.getJavaMailProperties();
        TIMEOUTS_PADRAO.forEach(props::setProperty);
        props.setProperty("mail.smtp.auth", String.valueOf(config.isAuth()));
        props.setProperty("mail.smtp.starttls.enable", String.valueOf(config.isStartTls()));
        // por ultimo: escape hatch para o que o tipado nao modela
        config.getProperties().forEach(props::setProperty);

        return new MailAccount(config.getName(), sender, config.getMaxPerMinute());
    }
}
