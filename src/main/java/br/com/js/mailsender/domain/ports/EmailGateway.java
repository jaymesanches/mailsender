package br.com.js.mailsender.domain.ports;

import br.com.js.mailsender.domain.model.EmailMessage;

public interface EmailGateway {

    /**
     * @return nome da conta que aceitou a mensagem — as contas sao intercambiaveis,
     *         mas saber qual entregou e o que permite auditar o consumo do limite
     *         diario e atribuir throttling a uma caixa especifica.
     */
    String send(EmailMessage emailMessage);
}
