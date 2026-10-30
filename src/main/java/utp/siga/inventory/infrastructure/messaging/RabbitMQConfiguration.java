package utp.siga.inventory.infrastructure.messaging;

import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfiguration {
    @Bean
    TopicExchange eventsExchange(@Value("${inventory.catalog.consumer.exchange}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    Queue catalogEventQueue(@Value("${inventory.catalog.consumer.queue}") String queue) {
        return QueueBuilder.durable(queue)
                .deadLetterExchange(queue + ".dlx")
                .deadLetterRoutingKey(queue + ".dead")
                .build();
    }

    @Bean
    DirectExchange catalogDeadLetterExchange(@Value("${inventory.catalog.consumer.queue}") String queue) {
        return new DirectExchange(queue + ".dlx", true, false);
    }

    @Bean
    Queue catalogDeadLetterQueue(@Value("${inventory.catalog.consumer.queue}") String queue) {
        return QueueBuilder.durable(queue + ".dlq").build();
    }

    @Bean
    Binding catalogDeadLetterBinding(
            @Qualifier("catalogDeadLetterQueue") Queue deadLetterQueue,
            DirectExchange catalogDeadLetterExchange,
            @Value("${inventory.catalog.consumer.queue}") String queue) {
        return BindingBuilder.bind(deadLetterQueue).to(catalogDeadLetterExchange).with(queue + ".dead");
    }

    @Bean
    Binding productCreatedBinding(@Qualifier("catalogEventQueue") Queue queue, TopicExchange eventsExchange) {
        return bind(queue, eventsExchange, "catalog.product.created.v1");
    }

    @Bean
    Binding productUpdatedBinding(@Qualifier("catalogEventQueue") Queue queue, TopicExchange eventsExchange) {
        return bind(queue, eventsExchange, "catalog.product.updated.v1");
    }

    @Bean
    Binding supplierCreatedBinding(@Qualifier("catalogEventQueue") Queue queue, TopicExchange eventsExchange) {
        return bind(queue, eventsExchange, "catalog.supplier.created.v1");
    }

    @Bean
    Binding supplierUpdatedBinding(@Qualifier("catalogEventQueue") Queue queue, TopicExchange eventsExchange) {
        return bind(queue, eventsExchange, "catalog.supplier.updated.v1");
    }

    @Bean
    Binding supplierDisabledBinding(@Qualifier("catalogEventQueue") Queue queue, TopicExchange eventsExchange) {
        return bind(queue, eventsExchange, "catalog.supplier.disabled.v1");
    }

    @Bean
    SimpleRabbitListenerContainerFactory catalogEventListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            @Value("${inventory.catalog.consumer.retry.max-attempts}") int maxAttempts,
            @Value("${inventory.catalog.consumer.retry.initial-interval-ms}") long initialInterval,
            @Value("${inventory.catalog.consumer.retry.multiplier}") double multiplier,
            @Value("${inventory.catalog.consumer.retry.max-interval-ms}") long maxInterval) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setConcurrentConsumers(2);
        Advice retry = RetryInterceptorBuilder.stateless()
                .maxAttempts(maxAttempts)
                .backOffOptions(initialInterval, multiplier, maxInterval)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
        factory.setAdviceChain(retry);
        return factory;
    }

    private Binding bind(Queue queue, TopicExchange exchange, String routingKey) {
        return BindingBuilder.bind(queue).to(exchange).with(routingKey);
    }
}
