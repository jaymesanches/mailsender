package br.com.js.mailsender.infrastructure.mail;

import br.com.js.mailsender.domain.model.EmailMessage;
import br.com.js.mailsender.domain.model.PermanentMailFailure;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class SpringEmailGateway implements EmailGateway {

    private final JavaMailSender javaMailSender;

    @Override
    public void send(EmailMessage emailMessage) {
        MimeMessage message;
        try {
            message = build(emailMessage);
        } catch (MessagingException e) {
            // mensagem malformada: retentar nao muda o resultado
            throw new PermanentMailFailure("Failed to construct email message", e);
        }

        try {
            javaMailSender.send(message);
        } catch (MailException e) {
            throw classify(e);
        }
    }

    private MimeMessage build(EmailMessage emailMessage) throws MessagingException {
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
     * Traduz a falha do SMTP em transitoria ou permanente. Mantem o detalhe do
     * provedor confinado aqui: o consumidor decide o estado so a partir do tipo.
     */
    private RuntimeException classify(MailException e) {
        if (hasRejectedRecipient(e)) {
            return new PermanentMailFailure("Recipient permanently rejected: " + e.getMessage(), e);
        }

        if (e instanceof MailSendException sendException) {
            for (Exception failure : sendException.getFailedMessages().values()) {
                if (hasRejectedRecipient(failure)) {
                    return new PermanentMailFailure("Recipient permanently rejected: " + failure.getMessage(), e);
                }
            }
        }

        return new TransientMailFailure("Transient mail failure: " + e.getMessage(), e);
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
