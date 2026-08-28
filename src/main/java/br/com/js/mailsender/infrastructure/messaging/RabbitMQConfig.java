package br.com.js.mailsender.infrastructure.messaging;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EMAIL_QUEUE = "emails.send.queue";
    public static final String DLQ_QUEUE = "emails.send.dlq";
    public static final String EMAIL_EXCHANGE = "emails.exchange";
    public static final String EMAIL_ROUTING_KEY = "emails.send.key";
    public static final String EMAIL_DLQ_ROUTING_KEY = "emails.dlq.key";
    public static final String PARKING_QUEUE = "emails.send.parking";
    public static final String EMAIL_PARKING_ROUTING_KEY = "emails.parking.key";

    @Bean
    public DirectExchange emailExchange() {
        return new DirectExchange(EMAIL_EXCHANGE);
    }

    @Bean
    public Queue emailQueue() {
        return QueueBuilder.durable(EMAIL_QUEUE)
                .withArgument("x-dead-letter-exchange", EMAIL_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", EMAIL_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue emailDLQ() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    @Bean
    public Binding emailBinding() {
        return BindingBuilder.bind(emailQueue()).to(emailExchange()).with(EMAIL_ROUTING_KEY);
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(emailDLQ()).to(emailExchange()).with(EMAIL_DLQ_ROUTING_KEY);
    }

    /** Fim da linha: ninguem consome, existe para inspecao manual. */
    @Bean
    public Queue emailParkingQueue() {
        return QueueBuilder.durable(PARKING_QUEUE).build();
    }

    @Bean
    public Binding parkingBinding() {
        return BindingBuilder.bind(emailParkingQueue()).to(emailExchange()).with(EMAIL_PARKING_ROUTING_KEY);
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public MessageRecoverer messageRecoverer(RabbitTemplate rabbitTemplate) {
        // Envia explicitamente para a DLQ quando esgotar os retries,
        // evitando depender da re-criação da fila no RabbitMQ para setar as policies de
        // x-dead-letter
        var paraDlq = new RepublishMessageRecoverer(rabbitTemplate, EMAIL_EXCHANGE, EMAIL_DLQ_ROUTING_KEY);
        var paraParking = new RepublishMessageRecoverer(rabbitTemplate, EMAIL_EXCHANGE, EMAIL_PARKING_ROUTING_KEY);

        // O recoverer vale para todos os listeners, inclusive o da propria DLQ. Sem
        // ramificar pela fila de origem, uma falha ao consumir a DLQ republicaria na DLQ
        // e a mensagem circularia para sempre.
        return (message, cause) -> {
            var filaDeOrigem = message.getMessageProperties().getConsumerQueue();
            if (DLQ_QUEUE.equals(filaDeOrigem)) {
                paraParking.recover(message, cause);
            } else {
                paraDlq.recover(message, cause);
            }
        };
    }
}
