package br.com.js.mailsender.presentation.controllers;

import br.com.js.mailsender.application.dtos.EmailResponse;
import br.com.js.mailsender.application.dtos.SendEmailRequest;
import br.com.js.mailsender.application.usecases.SendEmailUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/emails")
@RequiredArgsConstructor
public class EmailController {

    private final SendEmailUseCase sendEmailUseCase;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EmailResponse> sendEmail(@ModelAttribute SendEmailRequest request) {
        var response = sendEmailUseCase.execute(request);
        return ResponseEntity
                .created(URI.create("/api/v1/emails/" + response.id()))
                .body(response);
    }
}
