package br.com.js.mailsender.infrastructure.mail;

/**
 * Porta do controle de taxa. A implementacao em memoria serve uma instancia; com
 * duas ou mais, trocar por uma implementacao distribuida — senao cada instancia
 * conta o seu proprio limite e o total estoura o do provedor.
 */
public interface SendRateLimiter {

    boolean tryAcquire(String accountName, int maxPerMinute);
}
