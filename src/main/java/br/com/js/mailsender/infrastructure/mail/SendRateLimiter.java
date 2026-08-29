package br.com.js.mailsender.infrastructure.mail;

/**
 * Porta do controle de taxa. Uma implementacao distribuida (Redis, por exemplo)
 * substitui a de memoria quando a aplicacao passar a rodar em mais de uma
 * instancia — o `MailAccountPool` avisa no boot enquanto a de memoria estiver ativa.
 */
public interface SendRateLimiter {

    boolean tryAcquire(String accountName, int maxPerMinute);
}
