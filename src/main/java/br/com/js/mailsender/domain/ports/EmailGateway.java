package br.com.js.mailsender.domain.ports;

import br.com.js.mailsender.domain.model.EmailMessage;

public interface EmailGateway {
    void send(EmailMessage emailMessage);
}
