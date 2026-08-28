package br.com.js.mailsender.infrastructure.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MessageRecovererRoutingTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private MessageRecoverer recoverer;

    @BeforeEach
    void setUp() {
        recoverer = new RabbitMQConfig().messageRecoverer(rabbitTemplate);
    }

    @Test
    void falhaNaFilaPrincipalVaiParaDlq() {
        recoverer.recover(mensagemConsumidaDe(RabbitMQConfig.EMAIL_QUEUE), new RuntimeException("erro"));

        verify(rabbitTemplate).send(
                eq(RabbitMQConfig.EMAIL_EXCHANGE),
                eq(RabbitMQConfig.EMAIL_DLQ_ROUTING_KEY),
                any(Message.class));
    }

    @Test
    void falhaNaDlqVaiParaParkingEmVezDeVoltarParaDlq() {
        recoverer.recover(mensagemConsumidaDe(RabbitMQConfig.DLQ_QUEUE), new RuntimeException("erro"));

        verify(rabbitTemplate).send(
                eq(RabbitMQConfig.EMAIL_EXCHANGE),
                eq(RabbitMQConfig.EMAIL_PARKING_ROUTING_KEY),
                any(Message.class));
        // republicar na propria DLQ faria a mensagem circular para sempre
        verify(rabbitTemplate, never()).send(
                anyString(),
                eq(RabbitMQConfig.EMAIL_DLQ_ROUTING_KEY),
                any(Message.class));
    }

    private static Message mensagemConsumidaDe(String fila) {
        var properties = new MessageProperties();
        properties.setConsumerQueue(fila);
        return MessageBuilder.withBody("{}".getBytes()).andProperties(properties).build();
    }
}
