package br.com.js.mailsender.infrastructure.messaging;

import br.com.js.mailsender.domain.ports.EmailDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RabbitEmailDispatcher implements EmailDispatcher {

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void enqueue(UUID emailId) {
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EMAIL_EXCHANGE,
                RabbitMQConfig.EMAIL_ROUTING_KEY,
                new EmailEnqueuedEvent(emailId));
    }
}
