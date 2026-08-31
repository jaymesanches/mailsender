package br.com.js.mailsender.domain.model;

public class AttachmentTooLargeException extends RuntimeException {

    public AttachmentTooLargeException(long totalBytes, long limitBytes) {
        super("""
                Anexos somam %s, acima do limite de %s. O provedor limita a mensagem MIME \
                codificada, e base64 infla os bytes em cerca de um terco."""
                .formatted(mb(totalBytes), mb(limitBytes)));
    }

    private static String mb(long bytes) {
        return "%.1f MB".formatted(bytes / (1024.0 * 1024.0));
    }
}
