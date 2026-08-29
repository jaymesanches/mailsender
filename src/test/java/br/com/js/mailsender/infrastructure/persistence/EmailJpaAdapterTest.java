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
import org.springframework.data.domain.Limit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @Captor
    private ArgumentCaptor<Limit> limitCaptor;

    @Test
    void saveDeveMapearDominioParaEntidadeComRetrovinculoDoAnexo() {
        var domain = EmailMessage.reconstitute(UUID.randomUUID(), Email.of("dest@example.com"), "assunto", "corpo",
                true, List.of(EmailAttachment.fromStorage("doc.txt", "text/plain", "chave/doc.txt")),
                EmailStatus.FAILED, CRIADO_EM, null, 2, "SMTP fora do ar");

        var result = adapter.save(domain);

        verify(repository).saveAndFlush(entityCaptor.capture());
        var entity = entityCaptor.getValue();
        assertThat(entity.getId()).isEqualTo(domain.getId());
        assertThat(entity.getRecipient()).isEqualTo("dest@example.com");
        assertThat(entity.getSubject()).isEqualTo("assunto");
        assertThat(entity.getBody()).isEqualTo("corpo");
        assertThat(entity.isHtml()).isTrue();
        assertThat(entity.getStatus()).isEqualTo(EmailStatus.FAILED);
        assertThat(entity.getCreatedAt()).isEqualTo(CRIADO_EM);
        assertThat(entity.getSentAt()).isNull();
        assertThat(entity.getAttempts()).isEqualTo(2);
        assertThat(entity.getLastError()).isEqualTo("SMTP fora do ar");
        assertThat(entity.getAttachments()).singleElement().satisfies(att -> {
            assertThat(att.getName()).isEqualTo("doc.txt");
            assertThat(att.getContentType()).isEqualTo("text/plain");
            assertThat(att.getStoragePath()).isEqualTo("chave/doc.txt");
            assertThat(att.getEmail()).isSameAs(entity);
        });

        assertThat(result.getId()).isEqualTo(domain.getId());
        assertThat(result.getStatus()).isEqualTo(EmailStatus.FAILED);
        assertThat(result.getAttempts()).isEqualTo(2);
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
        assertThat(domain.getAttempts()).isEqualTo(1);
        assertThat(domain.getAttachments()).singleElement()
                .satisfies(att -> assertThat(att.getStoragePath()).isEqualTo("chave/doc.txt"));
    }

    @Test
    void findByIdDeveRetornarVazioQuandoNaoExiste() {
        var id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThat(adapter.findById(id)).isEmpty();
    }

    @Test
    void findRetriableIdsDeveFiltrarFalhasDentroDoLimiteDeTentativas() {
        var esperados = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(repository.findRetriableIds(eq(EmailStatus.FAILED), eq(EmailMessage.MAX_ATTEMPTS), any(Limit.class)))
                .thenReturn(esperados);

        var ids = adapter.findRetriableIds(10);

        assertThat(ids).isEqualTo(esperados);
        verify(repository).findRetriableIds(eq(EmailStatus.FAILED), eq(EmailMessage.MAX_ATTEMPTS),
                limitCaptor.capture());
        assertThat(limitCaptor.getValue().max()).isEqualTo(10);
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
        entity.setAttempts(1);

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
