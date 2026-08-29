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

    /**
     * Manda para a sala de espera do throttling. Fora da port de proposito: ciclo de
     * espera e conceito de mensageria, e o dominio nao precisa conhece-lo.
     */
    public void enqueueAfterWait(UUID emailId, int cycle) {
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EMAIL_EXCHANGE,
                RabbitMQConfig.EMAIL_WAIT_ROUTING_KEY,
                new EmailEnqueuedEvent(emailId),
                message -> {
                    message.getMessageProperties().setHeader(RabbitMQConfig.THROTTLE_CYCLE_HEADER, cycle);
                    return message;
                });
    }
}
