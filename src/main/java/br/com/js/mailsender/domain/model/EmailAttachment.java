package br.com.js.mailsender.domain.model;

public record EmailAttachment(String name, String contentType, byte[] content) {
}
