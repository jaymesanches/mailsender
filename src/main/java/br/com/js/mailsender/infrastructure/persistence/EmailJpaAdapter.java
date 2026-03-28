package br.com.js.mailsender.infrastructure.persistence;

import br.com.js.mailsender.domain.model.Email;
import br.com.js.mailsender.domain.model.EmailAttachment;
import br.com.js.mailsender.domain.model.EmailMessage;
import br.com.js.mailsender.domain.ports.EmailRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class EmailJpaAdapter implements EmailRepository {

    private final EmailJpaRepository repository;

    @Override
    public EmailMessage save(EmailMessage emailMessage) {
        var entity = toEntity(emailMessage);
        repository.save(entity);
        return toDomain(entity);
    }

    @Override
    public Optional<EmailMessage> findById(UUID id) {
        return repository.findById(id).map(this::toDomain);
    }

    private EmailJpaEntity toEntity(EmailMessage domain) {
        var entity = new EmailJpaEntity(
                domain.getId(),
                domain.getTo().value(),
                domain.getSubject(),
                domain.getBody(),
                domain.isHtml(),
                domain.getStatus(),
                new ArrayList<>(),
                domain.getCreatedAt(),
                domain.getSentAt());

        if (domain.getAttachments() != null) {
            List<EmailAttachmentJpaEntity> attachmentEntities = domain.getAttachments().stream()
                    .map(att -> {
                        EmailAttachmentJpaEntity attEntity = new EmailAttachmentJpaEntity();
                        attEntity.setName(att.getName());
                        attEntity.setContentType(att.getContentType());
                        attEntity.setStoragePath(att.getStoragePath());
                        attEntity.setEmail(entity);
                        return attEntity;
                    })
                    .toList();
            entity.getAttachments().addAll(attachmentEntities);
        }

        return entity;
    }

    private EmailMessage toDomain(EmailJpaEntity entity) {
        List<EmailAttachment> attachments = entity.getAttachments() != null ? entity.getAttachments().stream()
                .map(attEntity -> EmailAttachment.fromStorage(
                        attEntity.getName(),
                        attEntity.getContentType(),
                        attEntity.getStoragePath()))
                .toList() : new ArrayList<>();

        return EmailMessage.reconstitute(
                entity.getId(),
                Email.of(entity.getRecipient()),
                entity.getSubject(),
                entity.getBody(),
                entity.isHtml(),
                attachments,
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getSentAt());
    }
}
