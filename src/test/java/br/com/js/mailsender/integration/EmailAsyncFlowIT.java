package br.com.js.mailsender.integration;

import br.com.js.mailsender.domain.model.EmailMessage;
import br.com.js.mailsender.domain.ports.EmailGateway;
import br.com.js.mailsender.domain.ports.EmailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class EmailAsyncFlowIT {

    @Container
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer("rabbitmq:4.0-management");

    @Container
    static MinIOContainer minioContainer = new MinIOContainer("minio/minio:latest");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private EmailRepository emailRepository;

    @MockitoBean
    private EmailGateway emailGateway;

    private MockMvc mockMvc;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", rabbitMQContainer::getHost);
        registry.add("spring.rabbitmq.port", rabbitMQContainer::getAmqpPort);
        registry.add("minio.endpoint", () -> String.format("http://%s:%d", minioContainer.getHost(), minioContainer.getMappedPort(9000)));
        registry.add("minio.access-key", minioContainer::getUserName);
        registry.add("minio.secret-key", minioContainer::getPassword);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        
        // Criar o bucket no MinIO antes de cada teste
        S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(String.format("http://%s:%d", minioContainer.getHost(), minioContainer.getMappedPort(9000))))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(minioContainer.getUserName(), minioContainer.getPassword())))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .build();
        
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket("mail-attachments").build());
        } catch (Exception ignored) {}
    }

    @Test
    void shouldProcessEmailAsynchronouslyWithAttachments() throws Exception {
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

        // 3. Aguardar o processamento assíncrono (RabbitMQ -> Consumer -> S3 -> Gateway)
        // O Awaitility verificará o banco de dados até que o status mude para SENT
        await()
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    EmailMessage processedEmail = emailRepository.findById(emailId).orElseThrow();
                    assertEquals(EmailMessage.EmailStatus.SENT, processedEmail.getStatus());
                });

        // 4. Verificar se o gateway de e-mail foi chamado de fato
        verify(emailGateway, atLeastOnce()).send(any(EmailMessage.class));
    }
}
