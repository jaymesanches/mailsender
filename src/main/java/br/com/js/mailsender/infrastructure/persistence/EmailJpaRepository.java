package br.com.js.mailsender.infrastructure.persistence;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EmailJpaRepository extends JpaRepository<EmailJpaEntity, UUID> {

    @Override
    @EntityGraph(attributePaths = { "attachments" })
    Optional<EmailJpaEntity> findById(UUID id);
}
