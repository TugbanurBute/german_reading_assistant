package com.germanreader.processing;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.StatelessRetryOperationsInterceptor;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class DocumentProcessingConfig {

    public static final String EXCHANGE = "documents";
    public static final String ROUTING_KEY = "document.processing.requested";
    public static final String QUEUE = "document.processing";
    public static final String DEAD_LETTER_EXCHANGE = "documents.dlx";
    public static final String DEAD_LETTER_QUEUE = "document.processing.dlq";

    static final int MAX_ATTEMPTS = 3;

    @Bean
    DirectExchange documentsExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue documentProcessingQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_QUEUE)
                .build();
    }

    @Bean
    Binding documentProcessingBinding() {
        return BindingBuilder.bind(documentProcessingQueue()).to(documentsExchange()).with(ROUTING_KEY);
    }

    @Bean
    DirectExchange documentsDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue documentProcessingDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding documentProcessingDeadLetterBinding() {
        return BindingBuilder.bind(documentProcessingDeadLetterQueue()).to(documentsDeadLetterExchange())
                .with(DEAD_LETTER_QUEUE);
    }

    /** JSON messages; used by RabbitTemplate and the listener (Spring Boot applies it to both). */
    @Bean
    MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            DocumentProcessor processor) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(retry(processor, 1000, 5000));
        return factory;
    }

    static StatelessRetryOperationsInterceptor retry(DocumentProcessor processor, long initialBackoffMs, long maxBackoffMs) {
        RejectAndDontRequeueRecoverer reject = new RejectAndDontRequeueRecoverer();
        return RetryInterceptorBuilder.stateless()
                .maxRetries(MAX_ATTEMPTS - 1)
                .backOffOptions(initialBackoffMs, 2.0, maxBackoffMs)
                .recoverer((message, cause) -> {
                    processor.markFailed(message, cause);
                    reject.recover(message, cause);
                })
                .build();
    }
}
