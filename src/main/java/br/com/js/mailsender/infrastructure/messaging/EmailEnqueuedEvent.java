package br.com.js.mailsender.infrastructure.messaging;

import java.util.UUID;

public record EmailEnqueuedEvent(UUID emailId) {}
