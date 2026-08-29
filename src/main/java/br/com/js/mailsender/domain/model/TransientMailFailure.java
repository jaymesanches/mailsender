package br.com.js.mailsender.domain.model;

/** Falha que pode passar: SMTP fora do ar, timeout, autenticacao. Vale retentar. */
public class TransientMailFailure extends RuntimeException {

    public TransientMailFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
