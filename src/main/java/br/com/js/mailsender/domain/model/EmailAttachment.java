package br.com.js.mailsender.domain.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class EmailAttachment {
    private final String name;
    private final String contentType;
    private final byte[] content;
    private String storagePath;

    public static EmailAttachment fromUpload(String name, String contentType, byte[] content) {
        return new EmailAttachment(name, contentType, content, null);
    }

    public static EmailAttachment fromStorage(String name, String contentType, String storagePath) {
        return new EmailAttachment(name, contentType, null, storagePath);
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }
}
