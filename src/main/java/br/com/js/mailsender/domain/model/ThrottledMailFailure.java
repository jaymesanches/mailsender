package br.com.js.mailsender.domain.model;

/**
 * O servidor recusou por limite de taxa. Nao e falha do e-mail: e falta de
 * capacidade agora. Esperar a janela virar resolve; insistir em segundos, nao.
 */
public class ThrottledMailFailure extends MailFailure {

    public ThrottledMailFailure(String message, String account, Throwable cause) {
        super(message, account, cause);
    }
}
