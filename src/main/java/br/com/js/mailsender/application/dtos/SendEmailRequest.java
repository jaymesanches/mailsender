package br.com.js.mailsender.application.dtos;

public record SendEmailRequest(String to, String subject, String body) {
}
