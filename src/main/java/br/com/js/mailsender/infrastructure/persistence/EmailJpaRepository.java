package br.com.js.mailsender.infrastructure.persistence;

import br.com.js.mailsender.domain.model.EmailMessage.EmailStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmailJpaRepository extends JpaRepository<EmailJpaEntity, UUID> {

    @Override
    @EntityGraph(attributePaths = { "attachments" })
    Optional<EmailJpaEntity> findById(UUID id);

    @Query("""
            SELECT e.id FROM EmailJpaEntity e
            WHERE e.status = :status AND e.attempts < :maxAttempts
            ORDER BY e.createdAt
            """)
    List<UUID> findRetriableIds(@Param("status") EmailStatus status,
            @Param("maxAttempts") int maxAttempts,
            Limit limit);
}
