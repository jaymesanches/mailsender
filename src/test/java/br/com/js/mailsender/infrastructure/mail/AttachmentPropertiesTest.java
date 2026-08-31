package br.com.js.mailsender.infrastructure.mail;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentPropertiesTest {

    @Test
    void orcamentoDeBytesCrusEhMenorQueOLimiteDoProvedor() {
        var props = new AttachmentProperties();

        // 25MB de mensagem codificada cabem ~18MB de anexo cru
        assertThat(props.maxRawAttachmentBytes())
                .isLessThan(DataSize.ofMegabytes(25).toBytes())
                .isBetween(DataSize.ofMegabytes(18).toBytes(), DataSize.ofMegabytes(19).toBytes());
    }

    @Test
    void deveAcompanharOLimiteDoProvedorQuandoOAdminLibera() {
        var props = new AttachmentProperties();
        props.setMaxMessageSize(DataSize.ofMegabytes(150));

        assertThat(props.maxRawAttachmentBytes())
                .isBetween(DataSize.ofMegabytes(109).toBytes(), DataSize.ofMegabytes(110).toBytes());
    }
}
