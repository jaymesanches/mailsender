package br.com.js.mailsender.infrastructure.mail;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@Getter
@Setter
@ConfigurationProperties(prefix = "mailsender.attachments")
public class AttachmentProperties {

    /**
     * Limite de tamanho de mensagem do provedor — o mesmo numero que aparece no
     * admin center. Exchange Online: 25MB por padrao, ate 150MB se o admin liberar.
     */
    private DataSize maxMessageSize = DataSize.ofMegabytes(25);

    /**
     * O limite do provedor vale para a mensagem MIME **codificada**. Base64 infla os
     * bytes em 1/3, e ainda entram quebras de linha a cada 76 caracteres e os
     * cabecalhos das partes. 1.37 e a folga que cobre isso.
     */
    private double encodingOverhead = 1.37;

    /** Orcamento de bytes crus: e o que a API pode aceitar sem o provedor recusar depois. */
    public long maxRawAttachmentBytes() {
        return (long) (maxMessageSize.toBytes() / encodingOverhead);
    }
}
