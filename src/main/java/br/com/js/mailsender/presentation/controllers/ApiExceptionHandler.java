package br.com.js.mailsender.presentation.controllers;

import br.com.js.mailsender.domain.model.AttachmentTooLargeException;
import br.com.js.mailsender.domain.model.EmailNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(EmailNotFoundException.class)
    public ProblemDetail handleNotFound(EmailNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** Transicao de estado invalida: ja enviado, rejeitado ou sem tentativa disponivel. */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleInvalidState(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /** Excede o orcamento de bytes crus derivado do limite do provedor. */
    @ExceptionHandler(AttachmentTooLargeException.class)
    public ProblemDetail handleAttachmentTooLarge(AttachmentTooLargeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
    }

    /**
     * Guarda externa do Spring: protege a memoria antes de a requisicao chegar ao use
     * case. Sem este handler viraria 500 em vez de 413.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,
                "Requisicao excede o tamanho maximo aceito pelo servidor");
    }

    /** Destinatario invalido vinha respondendo 500. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleInvalidArgument(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
