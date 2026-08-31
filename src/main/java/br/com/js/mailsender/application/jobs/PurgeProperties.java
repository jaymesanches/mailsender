package br.com.js.mailsender.application.jobs;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "mailsender.purge")
public class PurgeProperties {

    /** Expurgo e irreversivel: precisa de um jeito de desligar sem deploy. */
    private boolean enabled = true;

    private String cron = "0 30 3 * * *";

    /** Contados a partir de createdAt, unico timestamp presente nos tres terminais. */
    private int retentionDays = 90;

    private int batchSize = 200;
}
