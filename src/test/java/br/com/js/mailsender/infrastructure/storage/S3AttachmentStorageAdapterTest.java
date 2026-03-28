package br.com.js.mailsender.infrastructure.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class S3AttachmentStorageAdapterTest {

    @Autowired
    private S3AttachmentStorageAdapter adapter;

    @Autowired
    private S3Client s3Client;

    private static boolean bucketCreated = false;

    @BeforeEach
    void setup() {
        if (!bucketCreated) {
            try {
                // Verifica se o bucket existe, se dar NoSuchBucketException cria.
                s3Client.headBucket(HeadBucketRequest.builder().bucket("mail-attachments").build());
            } catch (NoSuchBucketException e) {
                s3Client.createBucket(CreateBucketRequest.builder().bucket("mail-attachments").build());
            }
            bucketCreated = true;
        }
    }

    @Test
    void deveFazerUploadEDownloadComSucesso() {
        var emailId = UUID.randomUUID();
        var filename = "arquivo-teste.txt";
        var content = "Teste com MinIO local".getBytes();

        String path = adapter.upload(emailId, filename, content);

        assertNotNull(path);
        assertTrue(path.contains(emailId.toString()));
        assertTrue(path.contains(filename));

        byte[] downloaded = adapter.download(path);

        assertArrayEquals(content, downloaded);
    }
}
