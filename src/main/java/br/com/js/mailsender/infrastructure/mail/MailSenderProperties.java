package br.com.js.mailsender.infrastructure.mail;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "mailsender")
public class MailSenderProperties {

    /** Vazio: o pool usa o JavaMailSender do spring.mail.* como conta unica. */
    private List<Account> accounts = new ArrayList<>();

    @Getter
    @Setter
    public static class Account {
        private String name;
        private String host;
        private int port = 587;
        private String username;
        private String password;
        /** Limite do provedor. Exchange Online: 30/min por caixa. */
        private int maxPerMinute = 30;
        private boolean startTls = true;
        private boolean auth = true;
    }
}
