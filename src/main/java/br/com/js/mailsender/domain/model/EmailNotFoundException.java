package br.com.js.mailsender.domain.model;

import java.util.UUID;

public class EmailNotFoundException extends RuntimeException {

    public EmailNotFoundException(UUID emailId) {
        super("Email not found: " + emailId);
    }
}
