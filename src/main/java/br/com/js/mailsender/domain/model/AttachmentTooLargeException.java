package br.com.js.mailsender.domain.model;

import java.util.Locale;

public class AttachmentTooLargeException extends RuntimeException {

    public AttachmentTooLargeException(long totalBytes, long limitBytes) {
        super("""
                Anexos somam %s, acima do limite de %s. O provedor limita a mensagem MIME \
                codificada, e base64 infla os bytes em cerca de um terco."""
                .formatted(mb(totalBytes), mb(limitBytes)));
    }

    /** Locale.ROOT de proposito: mensagem de resposta HTTP nao pode variar com o host. */
    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
