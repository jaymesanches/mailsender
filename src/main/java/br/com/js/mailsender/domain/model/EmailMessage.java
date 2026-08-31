package br.com.js.mailsender.domain.model;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import lombok.Getter;

@Getter
public class EmailMessage {

    public static final int MAX_ATTEMPTS = 3;
    private static final int MAX_ERROR_LENGTH = 500;

    private final UUID id;
    private final Email to;
    private final String subject;
    private final String body;
    private final boolean html;
    private final List<EmailAttachment> attachments;
    private EmailStatus status;
    private final Instant createdAt;
    private Instant sentAt;
    private String lastAccount;
    private int attempts;
    private String lastError;

    private EmailMessage(Email to, String subject, String body, boolean html, List<EmailAttachment> attachments) {
        this.id = UUID.randomUUID();
        this.to = to;
        this.subject = subject;
        this.body = body;
        this.html = html;
        this.attachments = attachments != null ? List.copyOf(attachments) : Collections.emptyList();
        this.status = EmailStatus.PENDING;
        this.createdAt = Instant.now();
        this.attempts = 1;
    }

    // Private constructor for reconstitution
    private EmailMessage(UUID id, Email to, String subject, String body, boolean html,
            List<EmailAttachment> attachments, EmailStatus status, Instant createdAt, Instant sentAt,
            String lastAccount, int attempts, String lastError) {
        this.id = id;
        this.to = to;
        this.subject = subject;
        this.body = body;
        this.html = html;
        this.attachments = attachments != null ? List.copyOf(attachments) : Collections.emptyList();
        this.status = status;
        this.createdAt = createdAt;
        this.sentAt = sentAt;
        this.lastAccount = lastAccount;
        this.attempts = attempts;
        this.lastError = lastError;
    }

    public static EmailMessage create(Email to, String subject, String body, boolean html,
            List<EmailAttachment> attachments) {
        return new EmailMessage(to, subject, body, html, attachments);
    }

    public static EmailMessage reconstitute(UUID id, Email to, String subject, String body, boolean html,
            List<EmailAttachment> attachments, EmailStatus status, Instant createdAt, Instant sentAt,
            String lastAccount, int attempts, String lastError) {
        return new EmailMessage(id, to, subject, body, html, attachments, status, createdAt, sentAt,
                lastAccount, attempts, lastError);
    }

    public void markAsSent(String account) {
        requirePending();
        this.status = EmailStatus.SENT;
        this.sentAt = Instant.now();
        this.lastAccount = account;
        this.lastError = null;
    }

    /**
     * Guarda conta e motivo da tentativa **sem** mudar o status. E o que da
     * diagnostico a quem so recebe o id depois, como o consumidor da DLQ.
     */
    public void recordAttemptFailure(String account, String error) {
        requirePending();
        this.lastAccount = account;
        this.lastError = truncate(error);
    }

    /** Encerra em FALHA preservando o diagnostico registrado pelas tentativas. */
    public void markAsFailed() {
        requirePending();
        this.status = EmailStatus.FAILED;
    }

    /** Falha permanente: terminal, nunca reenviado. */
    public void markAsRejected(String error, String account) {
        requirePending();
        this.status = EmailStatus.REJECTED;
        this.lastAccount = account;
        this.lastError = truncate(error);
    }

    public void markForRetry() {
        if (this.status != EmailStatus.FAILED) {
            throw new IllegalStateException(
                    "Only failed emails can be retried, current status: " + this.status);
        }
        if (this.attempts >= MAX_ATTEMPTS) {
            throw new IllegalStateException(
                    "Retry limit reached for email " + this.id + " (" + this.attempts + "/" + MAX_ATTEMPTS + ")");
        }
        this.attempts++;
        this.status = EmailStatus.PENDING;
    }

    public boolean isRetriable() {
        return this.status == EmailStatus.FAILED && this.attempts < MAX_ATTEMPTS;
    }

    /** Os bytes so podem sair do storage quando o e-mail nunca mais sera enviado. */
    public boolean isPurgeable() {
        return this.status != EmailStatus.PENDING && !isRetriable();
    }

    /**
     * storagePath nulo passa a significar "bytes expurgados": o anexo existiu e nao
     * esta mais la. A guarda aqui e a rede que impede uma query errada de soltar os
     * bytes de um e-mail que o reenvio ainda alcancaria.
     */
    public void markAttachmentsPurged() {
        if (!isPurgeable()) {
            throw new IllegalStateException(
                    "Email ainda pode ser enviado: " + this.status + "/" + this.attempts);
        }
        this.attachments.forEach(attachment -> attachment.setStoragePath(null));
    }

    private void requirePending() {
        if (this.status != EmailStatus.PENDING) {
            throw new IllegalStateException("Email already processed");
        }
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }

    public enum EmailStatus {
        PENDING, SENT, FAILED, REJECTED
    }
}
