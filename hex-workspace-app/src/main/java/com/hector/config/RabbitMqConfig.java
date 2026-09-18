package com.hector.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * RabbitMQ 文档上传任务消息配置。
 */
@Configuration
public class RabbitMqConfig {

    @Value("${hexworkspace.mq.document-upload.exchange}")
    private String documentUploadExchange;

    @Value("${hexworkspace.mq.document-upload.queue}")
    private String documentUploadQueue;

    @Value("${hexworkspace.mq.document-upload.routing-key}")
    private String documentUploadRoutingKey;

    @Value("${hexworkspace.mq.knowledge-index.exchange}")
    private String knowledgeIndexExchange;

    @Value("${hexworkspace.mq.knowledge-index.queue}")
    private String knowledgeIndexQueue;

    @Value("${hexworkspace.mq.knowledge-index.routing-key}")
    private String knowledgeIndexRoutingKey;

    @Bean
    public TopicExchange documentExchange() {
        return new TopicExchange(documentUploadExchange, true, false);
    }

    @Bean
    public Queue documentUploadQueue() {
        return new Queue(documentUploadQueue, true);
    }

    @Bean
    public Binding documentUploadBinding(@Qualifier("documentUploadQueue") Queue documentUploadQueue,
                                        @Qualifier("documentExchange") TopicExchange documentExchange) {
        return BindingBuilder.bind(documentUploadQueue).to(documentExchange).with(documentUploadRoutingKey);
    }

    @Bean
    public TopicExchange knowledgeIndexExchange() {
        return new TopicExchange(knowledgeIndexExchange, true, false);
    }

    @Bean
    public Queue knowledgeIndexQueue() {
        return new Queue(knowledgeIndexQueue, true);
    }

    @Bean
    public Binding knowledgeIndexBinding(@Qualifier("knowledgeIndexQueue") Queue knowledgeIndexQueue,
                                         @Qualifier("knowledgeIndexExchange") TopicExchange knowledgeIndexExchange) {
        return BindingBuilder.bind(knowledgeIndexQueue).to(knowledgeIndexExchange).with(knowledgeIndexRoutingKey);
    }

    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
