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
            int attempts, String lastError) {
        this.id = id;
        this.to = to;
        this.subject = subject;
        this.body = body;
        this.html = html;
        this.attachments = attachments != null ? List.copyOf(attachments) : Collections.emptyList();
        this.status = status;
        this.createdAt = createdAt;
        this.sentAt = sentAt;
        this.attempts = attempts;
        this.lastError = lastError;
    }

    public static EmailMessage create(Email to, String subject, String body, boolean html,
            List<EmailAttachment> attachments) {
        return new EmailMessage(to, subject, body, html, attachments);
    }

    public static EmailMessage reconstitute(UUID id, Email to, String subject, String body, boolean html,
            List<EmailAttachment> attachments, EmailStatus status, Instant createdAt, Instant sentAt,
            int attempts, String lastError) {
        return new EmailMessage(id, to, subject, body, html, attachments, status, createdAt, sentAt,
                attempts, lastError);
    }

    public void markAsSent() {
        requirePending();
        this.status = EmailStatus.SENT;
        this.sentAt = Instant.now();
        this.lastError = null;
    }

    /** Falha transitoria: continua reenviavel enquanto houver tentativa disponivel. */
    public void markAsFailed(String error) {
        requirePending();
        this.status = EmailStatus.FAILED;
        this.lastError = truncate(error);
    }

    /** Falha permanente: terminal, nunca reenviado. */
    public void markAsRejected(String error) {
        requirePending();
        this.status = EmailStatus.REJECTED;
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
