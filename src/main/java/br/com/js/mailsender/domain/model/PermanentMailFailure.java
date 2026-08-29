package br.com.js.mailsender.domain.model;

/** Falha que nao vai passar: destinatario recusado em definitivo (5xx). Nao retentar. */
public class PermanentMailFailure extends RuntimeException {

    public PermanentMailFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
