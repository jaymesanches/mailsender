package br.com.js.mailsender.infrastructure.mail;

import br.com.js.mailsender.domain.model.EmailMessage;
import br.com.js.mailsender.domain.ports.EmailGateway;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
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
        try {
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

            javaMailSender.send(message);
        } catch (MessagingException e) {
            throw new RuntimeException("Failed to construct email message", e);
        }
    }
}
