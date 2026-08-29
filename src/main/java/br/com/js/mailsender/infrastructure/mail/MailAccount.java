package br.com.js.mailsender.infrastructure.mail;

import org.springframework.mail.javamail.JavaMailSender;

public record MailAccount(String name, JavaMailSender sender, int maxPerMinute) {
}
