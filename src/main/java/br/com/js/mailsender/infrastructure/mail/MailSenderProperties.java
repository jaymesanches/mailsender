package br.com.js.mailsender.infrastructure.mail;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        /**
         * Endereco do remetente. Vazio usa o `username`, que e o correto para Exchange:
         * uma caixa so pode enviar como ela mesma. Preencha diferente apenas quando
         * houver permissao SendAs sobre outro endereco — senao o servidor recusa com
         * "SendAsDenied".
         */
        private String from;

        /** Nome de exibicao opcional: "Prefeitura de Osasco <protocolo@...>". */
        private String fromName;

        /**
         * Propriedades JavaMail cruas, aplicadas por ultimo — sobrescrevem inclusive os
         * timeouts padrao e o auth/startTls acima. Chave com ponto exige colchetes no
         * YAML: {@code properties: { "[mail.smtp.timeout]": 7000 }}.
         */
        private Map<String, String> properties = new LinkedHashMap<>();
    }
}
