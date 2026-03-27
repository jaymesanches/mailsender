package br.com.js.mailsender.infrastructure.persistence;

import br.com.js.mailsender.domain.model.Email;
import br.com.js.mailsender.domain.model.EmailMessage.EmailStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "emails")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class EmailJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String recipient;

    private String subject;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "is_html")
    private boolean isHtml;

    @Enumerated(EnumType.STRING)
    private EmailStatus status;

    @OneToMany(mappedBy = "email", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EmailAttachmentJpaEntity> attachments = new ArrayList<>();

    private Instant createdAt;
    private Instant sentAt;
}
