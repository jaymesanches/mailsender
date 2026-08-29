package br.com.js.mailsender.domain.model;

/** Falha que nao vai passar: destinatario recusado em definitivo (5xx). Nao retentar. */
public class PermanentMailFailure extends MailFailure {

    public PermanentMailFailure(String message, String account, Throwable cause) {
        super(message, account, cause);
    }
}
