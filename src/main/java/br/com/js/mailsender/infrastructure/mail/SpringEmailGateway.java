package br.com.js.mailsender.infrastructure.mail;

import br.com.js.mailsender.domain.model.EmailMessage;
import br.com.js.mailsender.domain.ports.EmailGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SpringEmailGateway implements EmailGateway {

    private final JavaMailSender javaMailSender;

    @Override
    public void send(EmailMessage emailMessage) {
        var message = new SimpleMailMessage();
        message.setTo(emailMessage.getTo().value());
        message.setSubject(emailMessage.getSubject());
        message.setText(emailMessage.getBody());
        javaMailSender.send(message);
    }
}
