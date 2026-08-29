package br.com.js.mailsender.infrastructure.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.List;
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
        props.put("mail.smtp.auth", String.valueOf(config.isAuth()));
        props.put("mail.smtp.starttls.enable", String.valueOf(config.isStartTls()));

        return new MailAccount(config.getName(), sender, config.getMaxPerMinute());
    }
}
