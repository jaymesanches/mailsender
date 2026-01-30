package br.com.js.mailsender.infrastructure.persistence;

import br.com.js.mailsender.domain.model.Email;
import br.com.js.mailsender.domain.model.EmailMessage;
import br.com.js.mailsender.domain.ports.EmailRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class EmailJpaAdapter implements EmailRepository {

    private final EmailJpaRepository repository;

    @Override
    public EmailMessage save(EmailMessage emailMessage) {
        EmailJpaEntity entity = toEntity(emailMessage);
        repository.save(entity);
        return toDomain(entity);
    }

    @Override
    public Optional<EmailMessage> findById(UUID id) {
        return repository.findById(id).map(this::toDomain);
    }

    private EmailJpaEntity toEntity(EmailMessage domain) {
        return new EmailJpaEntity(
                domain.getId(),
                domain.getTo().value(),
                domain.getSubject(),
                domain.getBody(),
                domain.getStatus(),
                domain.getCreatedAt(),
                domain.getSentAt());
    }

    private EmailMessage toDomain(EmailJpaEntity entity) {
        // Using reflection or a constructor/builder to reconstruct domain object
        // Since EmailMessage constructor is private, we should probably add a
        // reconstruction factory method in EmailMessage
        // But for this exercise I'll modify EmailMessage to allow reconstruction or
        // assume we can add a method there.
        // Let's add 'reconstitute' method to EmailMessage using replace tool in next
        // step if generic 'create' is not enough.
        // Actually, I can just cheat slightly and make the fields non-final or add a
        // reconstruction constructor package-private
        // But the Clean way is a static factory 'reconstitute'.
        // For now, I will use a trick:
        // I will assume I can update EmailMessage.java to add a reconstitution method.
        return EmailMessage.reconstitute(
                entity.getId(),
                Email.of(entity.getRecipient()),
                entity.getSubject(),
                entity.getBody(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getSentAt());
    }
}
