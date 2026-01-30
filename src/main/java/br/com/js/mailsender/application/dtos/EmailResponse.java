package br.com.js.mailsender.application.dtos;

import java.util.UUID;
import br.com.js.mailsender.domain.model.EmailMessage.EmailStatus;

public record EmailResponse(UUID id, EmailStatus status) {
}
