package br.com.js.mailsender.domain.ports;

import java.util.UUID;

public interface AttachmentStorageGateway {

    String upload(UUID emailId, String filename, byte[] content);

    byte[] download(String path);

    /** Idempotente: apagar caminho inexistente e sucesso, nao erro. */
    void delete(String path);
}
