package br.com.js.mailsender.infrastructure.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3AttachmentStorageAdapterTest {

    @Mock
    private S3Client s3Client;

    @InjectMocks
    private S3AttachmentStorageAdapter adapter;

    @Captor
    private ArgumentCaptor<PutObjectRequest> putCaptor;

    @Captor
    private ArgumentCaptor<GetObjectRequest> getCaptor;

    @Captor
    private ArgumentCaptor<DeleteObjectRequest> deleteCaptor;

    @Test
    void uploadDeveUsarChavePrefixadaPeloEmailIdEDevolverOCaminho() {
        var emailId = UUID.randomUUID();

        var path = adapter.upload(emailId, "doc.txt", "conteudo".getBytes());

        assertThat(path).isEqualTo(emailId + "/doc.txt");

        verify(s3Client).putObject(putCaptor.capture(), any(RequestBody.class));
        assertThat(putCaptor.getValue().bucket()).isEqualTo("mail-attachments");
        assertThat(putCaptor.getValue().key()).isEqualTo(path);
    }

    @Test
    void deleteDeveApagarPelaChaveNoBucketCerto() {
        adapter.delete("chave/doc.txt");

        verify(s3Client).deleteObject(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().bucket()).isEqualTo("mail-attachments");
        assertThat(deleteCaptor.getValue().key()).isEqualTo("chave/doc.txt");
    }

    @Test
    void downloadDeveBuscarPelaChaveEDevolverOsBytes() {
        var conteudo = "conteudo".getBytes();
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), conteudo));

        var bytes = adapter.download("chave/doc.txt");

        assertThat(bytes).containsExactly(conteudo);

        verify(s3Client).getObjectAsBytes(getCaptor.capture());
        assertThat(getCaptor.getValue().bucket()).isEqualTo("mail-attachments");
        assertThat(getCaptor.getValue().key()).isEqualTo("chave/doc.txt");
    }
}
