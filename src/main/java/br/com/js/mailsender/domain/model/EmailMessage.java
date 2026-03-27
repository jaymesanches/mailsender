package br.com.js.mailsender.domain.model;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import lombok.Getter;

@Getter
public class EmailMessage {
    private final UUID id;
    private final Email to;
    private final String subject;
    private final String body;
    private final boolean html;
    private final List<EmailAttachment> attachments;
    private EmailStatus status;
    private final Instant createdAt;
    private Instant sentAt;

    private EmailMessage(Email to, String subject, String body, boolean html, List<EmailAttachment> attachments) {
        this.id = UUID.randomUUID();
        this.to = to;
        this.subject = subject;
        this.body = body;
        this.html = html;
        this.attachments = attachments != null ? List.copyOf(attachments) : Collections.emptyList();
        this.status = EmailStatus.PENDING;
        this.createdAt = Instant.now();
    }

    // Private constructor for reconstitution
    private EmailMessage(UUID id, Email to, String subject, String body, boolean html, List<EmailAttachment> attachments, EmailStatus status, Instant createdAt, Instant sentAt) {
        this.id = id;
        this.to = to;
        this.subject = subject;
        this.body = body;
        this.html = html;
        this.attachments = attachments != null ? List.copyOf(attachments) : Collections.emptyList();
        this.status = status;
        this.createdAt = createdAt;
        this.sentAt = sentAt;
    }

    public static EmailMessage create(Email to, String subject, String body, boolean html, List<EmailAttachment> attachments) {
        return new EmailMessage(to, subject, body, html, attachments);
    }

    public static EmailMessage reconstitute(UUID id, Email to, String subject, String body, boolean html, List<EmailAttachment> attachments, EmailStatus status, Instant createdAt, Instant sentAt) {
        return new EmailMessage(id, to, subject, body, html, attachments, status, createdAt, sentAt);
    }

    public void markAsSent() {
        if (this.status != EmailStatus.PENDING) {
            throw new IllegalStateException("Email already processed");
        }
        this.status = EmailStatus.SENT;
        this.sentAt = Instant.now();
    }

    public void markAsFailed() {
        if (this.status != EmailStatus.PENDING) {
            throw new IllegalStateException("Email already processed");
        }
        this.status = EmailStatus.FAILED;
    }

    public enum EmailStatus {
        PENDING, SENT, FAILED
    }
}
