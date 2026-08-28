package br.com.js.mailsender.infrastructure.persistence;

import br.com.js.mailsender.domain.model.Email;
import br.com.js.mailsender.domain.model.EmailAttachment;
import br.com.js.mailsender.domain.model.EmailMessage;
import br.com.js.mailsender.domain.model.EmailMessage.EmailStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailJpaAdapterTest {

    private static final Instant CRIADO_EM = Instant.parse("2026-01-01T10:00:00Z");

    @Mock
    private EmailJpaRepository repository;

    @InjectMocks
    private EmailJpaAdapter adapter;

    @Captor
    private ArgumentCaptor<EmailJpaEntity> entityCaptor;

    @Test
    void saveDeveMapearDominioParaEntidadeComRetrovinculoDoAnexo() {
        var domain = EmailMessage.reconstitute(UUID.randomUUID(), Email.of("dest@example.com"), "assunto", "corpo",
                true, List.of(EmailAttachment.fromStorage("doc.txt", "text/plain", "chave/doc.txt")),
                EmailStatus.PENDING, CRIADO_EM, null);

        var result = adapter.save(domain);

        verify(repository).saveAndFlush(entityCaptor.capture());
        var entity = entityCaptor.getValue();
        assertThat(entity.getId()).isEqualTo(domain.getId());
        assertThat(entity.getRecipient()).isEqualTo("dest@example.com");
        assertThat(entity.getSubject()).isEqualTo("assunto");
        assertThat(entity.getBody()).isEqualTo("corpo");
        assertThat(entity.isHtml()).isTrue();
        assertThat(entity.getStatus()).isEqualTo(EmailStatus.PENDING);
        assertThat(entity.getCreatedAt()).isEqualTo(CRIADO_EM);
        assertThat(entity.getSentAt()).isNull();
        assertThat(entity.getAttachments()).singleElement().satisfies(att -> {
            assertThat(att.getName()).isEqualTo("doc.txt");
            assertThat(att.getContentType()).isEqualTo("text/plain");
            assertThat(att.getStoragePath()).isEqualTo("chave/doc.txt");
            assertThat(att.getEmail()).isSameAs(entity);
        });

        assertThat(result.getId()).isEqualTo(domain.getId());
        assertThat(result.getStatus()).isEqualTo(EmailStatus.PENDING);
        assertThat(result.getAttachments()).singleElement()
                .satisfies(att -> assertThat(att.getContent()).isNull());
    }

    @Test
    void findByIdDeveMapearEntidadeParaDominio() {
        var id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(entidadeEnviada(id)));

        var domain = adapter.findById(id).orElseThrow();

        assertThat(domain.getId()).isEqualTo(id);
        assertThat(domain.getTo().value()).isEqualTo("dest@example.com");
        assertThat(domain.isHtml()).isTrue();
        assertThat(domain.getStatus()).isEqualTo(EmailStatus.SENT);
        assertThat(domain.getCreatedAt()).isEqualTo(CRIADO_EM);
        assertThat(domain.getSentAt()).isEqualTo(CRIADO_EM.plusSeconds(5));
        assertThat(domain.getAttachments()).singleElement()
                .satisfies(att -> assertThat(att.getStoragePath()).isEqualTo("chave/doc.txt"));
    }

    @Test
    void findByIdDeveRetornarVazioQuandoNaoExiste() {
        var id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThat(adapter.findById(id)).isEmpty();
    }

    private static EmailJpaEntity entidadeEnviada(UUID id) {
        var entity = new EmailJpaEntity();
        entity.setId(id);
        entity.setRecipient("dest@example.com");
        entity.setSubject("assunto");
        entity.setBody("corpo");
        entity.setHtml(true);
        entity.setStatus(EmailStatus.SENT);
        entity.setCreatedAt(CRIADO_EM);
        entity.setSentAt(CRIADO_EM.plusSeconds(5));

        var attachment = new EmailAttachmentJpaEntity();
        attachment.setId(UUID.randomUUID());
        attachment.setName("doc.txt");
        attachment.setContentType("text/plain");
        attachment.setStoragePath("chave/doc.txt");
        attachment.setEmail(entity);
        entity.getAttachments().add(attachment);

        return entity;
    }
}
