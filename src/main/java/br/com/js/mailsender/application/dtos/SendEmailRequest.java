package br.com.js.mailsender.application.dtos;

import org.springframework.web.multipart.MultipartFile;
import java.util.List;

public record SendEmailRequest(String to, String subject, String body, Boolean isHtml, List<MultipartFile> attachments) {
}
