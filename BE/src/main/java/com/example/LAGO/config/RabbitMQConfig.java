package com.example.LAGO.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String TICK_QUEUE    = "tick.queue";
    public static final String TICK_EXCHANGE = "tick.exchange";
    public static final String TICK_ROUTING_KEY = "tick";

    @Bean
    public Queue tickQueue() {
        return QueueBuilder.durable(TICK_QUEUE).build();
    }

    @Bean
    public DirectExchange tickExchange() {
        return new DirectExchange(TICK_EXCHANGE);
    }

    @Bean
    public Binding tickBinding(Queue tickQueue, DirectExchange tickExchange) {
        return BindingBuilder.bind(tickQueue).to(tickExchange).with(TICK_ROUTING_KEY);
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         Jackson2JsonMessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }
}
