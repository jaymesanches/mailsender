package br.com.js.mailsender.domain.model;

/** Falha que pode passar: SMTP fora do ar, timeout, autenticacao. Vale retentar. */
public class TransientMailFailure extends MailFailure {

    public TransientMailFailure(String message, String account, Throwable cause) {
        super(message, account, cause);
    }
}
