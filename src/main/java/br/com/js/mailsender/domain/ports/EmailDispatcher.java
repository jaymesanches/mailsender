package br.com.js.mailsender.domain.ports;

import java.util.UUID;

/** Enfileira um e-mail ja persistido para envio assincrono. */
public interface EmailDispatcher {
    void enqueue(UUID emailId);
}
