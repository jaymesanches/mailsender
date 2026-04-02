package br.com.js.mailsender.integration;

import br.com.js.mailsender.domain.model.EmailMessage;
import br.com.js.mailsender.domain.ports.AttachmentStorageGateway;
import br.com.js.mailsender.domain.ports.EmailGateway;
import br.com.js.mailsender.domain.ports.EmailRepository;
import br.com.js.mailsender.infrastructure.messaging.EmailEnqueuedEvent;
import br.com.js.mailsender.infrastructure.messaging.EmailQueueConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmailAsyncFlowIT {

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
    private ConnectionFactory connectionFactory; // Impede que o Spring Boot tente conectar no RabbitMQ

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void shouldProcessEmailAsynchronouslyWithAttachments() throws Exception {
        // Setup mocks
        when(storageGateway.upload(any(), any(), any())).thenReturn("mock/path");
        when(storageGateway.download("mock/path")).thenReturn("dummy content".getBytes());

        // 1. Chamar a API via POST (Multipart)
        String to = "test@example.com";
        String subject = "Test Async Subject";
        String body = "Hello from async world!";

        var result = mockMvc.perform(multipart("/api/v1/emails")
                        .param("to", to)
                        .param("subject", subject)
                        .param("body", body)
                        .param("isHtml", "false"))
                .andExpect(status().isCreated())
                .andReturn();

        // Extrair o ID da resposta (URI: /api/v1/emails/{id})
        String location = result.getResponse().getHeader("Location");
        assertNotNull(location);
        UUID emailId = UUID.fromString(location.substring(location.lastIndexOf("/") + 1));

        // 2. Verificar se o e-mail foi salvo inicialmente como PENDING
        EmailMessage initialEmail = emailRepository.findById(emailId).orElseThrow();
        assertEquals(EmailMessage.EmailStatus.PENDING, initialEmail.getStatus());

        // 3. Simular o RabbitMQ recebendo a mensagem e chamando o consumidor
        emailQueueConsumer.consume(new EmailEnqueuedEvent(emailId));

        // 4. Verificar se o status mudou para SENT
        EmailMessage processedEmail = emailRepository.findById(emailId).orElseThrow();
        assertEquals(EmailMessage.EmailStatus.SENT, processedEmail.getStatus());

        // 5. Verificar se o gateway de e-mail foi chamado de fato
        verify(emailGateway, atLeastOnce()).send(any(EmailMessage.class));
    }
}
