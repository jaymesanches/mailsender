package br.com.js.mailsender.infrastructure.mail;

import br.com.js.mailsender.domain.model.EmailMessage;
import br.com.js.mailsender.domain.model.PermanentMailFailure;
import br.com.js.mailsender.domain.model.ThrottledMailFailure;
import br.com.js.mailsender.domain.model.TransientMailFailure;
import br.com.js.mailsender.domain.ports.EmailGateway;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class SpringEmailGateway implements EmailGateway {

    private static final Pattern ENHANCED_STATUS = Pattern.compile("\\b([45])\\.\\d+\\.\\d+\\b");

    private final MailAccountPool accountPool;

    @Override
    public String send(EmailMessage emailMessage) {
        // sem capacidade agora: a Fatia de espera trata como throttling, nao como falha
        var account = accountPool.acquire()
                .orElseThrow(() -> new ThrottledMailFailure(
                        "Nenhuma conta com capacidade disponivel no momento", null, null));

        MimeMessage message;
        try {
            message = build(account.sender(), emailMessage);
        } catch (MessagingException e) {
            // mensagem malformada: retentar nao muda o resultado
            throw new PermanentMailFailure("Failed to construct email message", account.name(), e);
        }

        try {
            account.sender().send(message);
        } catch (MailException e) {
            // a conta no log e o que permite atribuir throttling a uma caixa
            log.warn("Falha ao enviar {} pela conta {}: {}", emailMessage.getId(), account.name(), e.getMessage());
            throw classify(e, account.name());
        }

        log.debug("Email {} enviado pela conta {}", emailMessage.getId(), account.name());
        return account.name();
    }

    private MimeMessage build(JavaMailSender javaMailSender, EmailMessage emailMessage) throws MessagingException {
        MimeMessage message = javaMailSender.createMimeMessage();
        boolean hasAttachments = emailMessage.getAttachments() != null && !emailMessage.getAttachments().isEmpty();
        MimeMessageHelper helper = new MimeMessageHelper(message, hasAttachments, "UTF-8");

        helper.setTo(emailMessage.getTo().value());
        helper.setSubject(emailMessage.getSubject());
        helper.setText(emailMessage.getBody(), emailMessage.isHtml());

        if (hasAttachments) {
            for (var attachment : emailMessage.getAttachments()) {
                if (attachment.getContent() != null && attachment.getContent().length > 0) {
                    helper.addAttachment(attachment.getName(), new ByteArrayResource(attachment.getContent()) {
                        @Override
                        public String getFilename() {
                            return attachment.getName();
                        }
                    });
                } else {
                    log.warn("Attachment {} has no content to send", attachment.getName());
                }
            }
        }

        return message;
    }

    /**
     * Traduz a falha do SMTP em permanente, throttling ou transitoria. Mantem o
     * detalhe do provedor confinado aqui: o consumidor decide o estado so pelo tipo.
     * A ordem importa — permanente antes de throttling.
     */
    private RuntimeException classify(MailException e, String account) {
        if (hasRejectedRecipient(e)) {
            return new PermanentMailFailure("Recipient permanently rejected: " + e.getMessage(), account, e);
        }

        if (e instanceof MailSendException sendException) {
            for (Exception failure : sendException.getFailedMessages().values()) {
                if (hasRejectedRecipient(failure)) {
                    return new PermanentMailFailure("Recipient permanently rejected: " + failure.getMessage(),
                            account, e);
                }
            }
        }

        return switch (statusClass(e)) {
            case PERMANENT -> new PermanentMailFailure("Server rejected the message: " + e.getMessage(), account, e);
            case TEMPORARY -> new ThrottledMailFailure("Server asked to slow down: " + e.getMessage(), account, e);
            case UNKNOWN -> new TransientMailFailure("Transient mail failure: " + e.getMessage(), account, e);
        };
    }

    /**
     * Le a classe do codigo estendido (RFC 3463) na resposta do servidor: 4.x.x pede
     * espera, 5.x.x e definitivo. E padrao SMTP, nao detalhe da Microsoft — o
     * "432 4.3.2 sender thread limit exceeded" do Exchange cai em TEMPORARY.
     * Servidor que nao mande codigo estendido cai em UNKNOWN e mantem o retry de antes.
     */
    private static StatusClass statusClass(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            var matcher = ENHANCED_STATUS.matcher(String.valueOf(cause.getMessage()));
            if (matcher.find()) {
                return matcher.group(1).equals("5") ? StatusClass.PERMANENT : StatusClass.TEMPORARY;
            }
        }
        return StatusClass.UNKNOWN;
    }

    private enum StatusClass {
        PERMANENT, TEMPORARY, UNKNOWN
    }

    /** Endereco que o servidor recusou de vez aparece em getInvalidAddresses(). */
    private static boolean hasRejectedRecipient(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SendFailedException sendFailed) {
                var invalid = sendFailed.getInvalidAddresses();
                if (invalid != null && invalid.length > 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
