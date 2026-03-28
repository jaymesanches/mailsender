package br.com.js.mailsender.infrastructure.storage;

import br.com.js.mailsender.domain.ports.AttachmentStorageGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class S3AttachmentStorageAdapter implements AttachmentStorageGateway {

    private final S3Client s3Client;
    private final String bucketName = "mail-attachments";

    @Override
    public String upload(UUID emailId, String filename, byte[] content) {
        String key = String.format("%s/%s", emailId, filename);
        
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        s3Client.putObject(putObjectRequest, RequestBody.fromBytes(content));
        return key;
    }

    @Override
    public byte[] download(String path) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(path)
                .build();

        ResponseBytes<GetObjectResponse> objectAsBytes = s3Client.getObjectAsBytes(getObjectRequest);
        return objectAsBytes.asByteArray();
    }
}
