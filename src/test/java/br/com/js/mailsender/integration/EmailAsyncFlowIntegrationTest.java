package br.com.js.mailsender.integration;

import br.com.js.mailsender.domain.model.EmailMessage.EmailStatus;
import br.com.js.mailsender.domain.ports.AttachmentStorageGateway;
import br.com.js.mailsender.domain.ports.EmailGateway;
import br.com.js.mailsender.domain.ports.EmailRepository;
import br.com.js.mailsender.infrastructure.messaging.EmailEnqueuedEvent;
import br.com.js.mailsender.infrastructure.messaging.EmailQueueConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercita controller + use case + persistencia real (Postgres do compose.yaml).
 * SMTP, storage e broker sao mockados; o consumer e invocado direto no lugar do RabbitMQ.
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
    private RabbitTemplate rabbitTemplate;

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
        when(storageGateway.upload(any(), any(), any())).thenReturn("chave/doc.txt");
        when(storageGateway.download("chave/doc.txt")).thenReturn("conteudo".getBytes());

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
        when(storageGateway.upload(any(), any(), any())).thenReturn("chave/doc.txt");
        doThrow(new RuntimeException("Simulated Mail Server Error")).when(emailGateway).send(any());

        var emailId = postEmail("Test Retry Subject");
        var event = new EmailEnqueuedEvent(emailId);

        assertThatThrownBy(() -> emailQueueConsumer.consume(event)).isInstanceOf(AmqpException.class);

        assertThat(emailRepository.findById(emailId).orElseThrow().getStatus())
                .isEqualTo(EmailStatus.PENDING);

        emailQueueConsumer.consumeDlq(event);

        assertThat(emailRepository.findById(emailId).orElseThrow().getStatus())
                .isEqualTo(EmailStatus.FAILED);
    }

    private UUID postEmail(String subject) throws Exception {
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
