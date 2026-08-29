package br.com.js.mailsender.domain.model;

/** Falha de envio, carregando a conta que a produziu. */
public abstract class MailFailure extends RuntimeException {

    private final String account;

    protected MailFailure(String message, String account, Throwable cause) {
        super(message, cause);
        this.account = account;
    }

    /** Null quando a falha aconteceu antes de escolher conta (pool sem capacidade). */
    public String account() {
        return account;
    }
}
