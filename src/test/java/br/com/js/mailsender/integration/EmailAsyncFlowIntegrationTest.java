package br.com.js.mailsender.integration;

import br.com.js.mailsender.domain.model.EmailMessage.EmailStatus;
import br.com.js.mailsender.domain.model.PermanentMailFailure;
import br.com.js.mailsender.domain.model.TransientMailFailure;
import br.com.js.mailsender.domain.ports.AttachmentStorageGateway;
import br.com.js.mailsender.domain.ports.EmailDispatcher;
import br.com.js.mailsender.domain.ports.EmailGateway;
import br.com.js.mailsender.domain.ports.EmailRepository;
import br.com.js.mailsender.infrastructure.messaging.EmailEnqueuedEvent;
import br.com.js.mailsender.infrastructure.messaging.EmailQueueConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercita controller + use cases + persistencia real (Postgres do compose.yaml).
 * SMTP, storage e fila sao mockados; o consumer e invocado direto no lugar do RabbitMQ.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class EmailAsyncFlowIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private EmailRepository emailRepository;

    @Autowired
    private EmailQueueConsumer emailQueueConsumer;

    @MockitoBean
    private EmailGateway emailGateway;

    @MockitoBean
    private AttachmentStorageGateway storageGateway;

    @MockitoBean
    private EmailDispatcher emailDispatcher;

    @MockitoBean
    private ConnectionFactory connectionFactory; // impede o Spring Boot de conectar no RabbitMQ

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // @AutoConfigureMockMvc saiu do spring-boot-starter-test no Boot 4
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void devePersistirPendenteEChegarAEnviadoAoConsumirAFila() throws Exception {
        var emailId = postEmail("Test Async Subject");

        assertThat(emailRepository.findById(emailId).orElseThrow().getStatus())
                .isEqualTo(EmailStatus.PENDING);

        emailQueueConsumer.consume(new EmailEnqueuedEvent(emailId));

        var processado = emailRepository.findById(emailId).orElseThrow();
        assertThat(processado.getStatus()).isEqualTo(EmailStatus.SENT);
        assertThat(processado.getSentAt()).isNotNull();
    }

    @Test
    void deveSeguirPendenteNoRetryEIrParaFalhaSomenteNaDlq() throws Exception {
        doThrow(new TransientMailFailure("Simulated Mail Server Error", new RuntimeException()))
                .when(emailGateway).send(any());

        var emailId = postEmail("Test Retry Subject");
        var event = new EmailEnqueuedEvent(emailId);

        assertThatThrownBy(() -> emailQueueConsumer.consume(event)).isInstanceOf(AmqpException.class);

        assertThat(emailRepository.findById(emailId).orElseThrow().getStatus())
                .isEqualTo(EmailStatus.PENDING);

        emailQueueConsumer.consumeDlq(event);

        var falhado = emailRepository.findById(emailId).orElseThrow();
        assertThat(falhado.getStatus()).isEqualTo(EmailStatus.FAILED);
        assertThat(falhado.isRetriable()).isTrue();
    }

    @Test
    void falhaPermanenteDeveEncerrarEmRejectedSemDlq() throws Exception {
        doThrow(new PermanentMailFailure("550 usuario inexistente", new RuntimeException()))
                .when(emailGateway).send(any());

        var emailId = postEmail("Test Rejected Subject");

        emailQueueConsumer.consume(new EmailEnqueuedEvent(emailId));

        var rejeitado = emailRepository.findById(emailId).orElseThrow();
        assertThat(rejeitado.getStatus()).isEqualTo(EmailStatus.REJECTED);
        assertThat(rejeitado.isRetriable()).isFalse();
    }

    @Test
    void reenvioDeveVoltarAPendenteEEntregarNaSegundaTentativa() throws Exception {
        // 1. primeira tentativa falha e a DLQ registra FAILED
        doThrow(new TransientMailFailure("SMTP fora do ar", new RuntimeException()))
                .when(emailGateway).send(any());

        var emailId = postEmail("Test Resend Subject");
        var event = new EmailEnqueuedEvent(emailId);
        assertThatThrownBy(() -> emailQueueConsumer.consume(event)).isInstanceOf(AmqpException.class);
        emailQueueConsumer.consumeDlq(event);
        assertThat(emailRepository.findById(emailId).orElseThrow().getStatus()).isEqualTo(EmailStatus.FAILED);

        // 2. SMTP volta e o operador pede o reenvio
        reset(emailGateway);
        mockMvc.perform(post("/api/v1/emails/{id}/reenvio", emailId))
                .andExpect(status().isAccepted());

        var reenfileirado = emailRepository.findById(emailId).orElseThrow();
        assertThat(reenfileirado.getStatus()).isEqualTo(EmailStatus.PENDING);
        assertThat(reenfileirado.getAttempts()).isEqualTo(2);

        // 3. o consumo seguinte entrega
        emailQueueConsumer.consume(event);
        assertThat(emailRepository.findById(emailId).orElseThrow().getStatus()).isEqualTo(EmailStatus.SENT);
    }

    @Test
    void reenvioDeEmailEnviadoDeveResponder409() throws Exception {
        var emailId = postEmail("Test Conflict Subject");
        emailQueueConsumer.consume(new EmailEnqueuedEvent(emailId));
        assertThat(emailRepository.findById(emailId).orElseThrow().getStatus()).isEqualTo(EmailStatus.SENT);

        mockMvc.perform(post("/api/v1/emails/{id}/reenvio", emailId))
                .andExpect(status().isConflict());
    }

    @Test
    void reenvioDeEmailInexistenteDeveResponder404() throws Exception {
        mockMvc.perform(post("/api/v1/emails/{id}/reenvio", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void destinatarioInvalidoDeveResponder400() throws Exception {
        mockMvc.perform(multipart("/api/v1/emails")
                .param("to", "nao-e-email")
                .param("subject", "assunto")
                .param("body", "corpo"))
                .andExpect(status().isBadRequest());
    }

    private UUID postEmail(String subject) throws Exception {
        when(storageGateway.upload(any(), any(), any())).thenReturn("chave/doc.txt");

        MvcResult result = mockMvc.perform(multipart("/api/v1/emails")
                .param("to", "test@example.com")
                .param("subject", subject)
                .param("body", "corpo")
                .param("isHtml", "false"))
                .andExpect(status().isCreated())
                .andReturn();

        var location = result.getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }
}
