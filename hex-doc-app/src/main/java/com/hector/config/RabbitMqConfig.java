package com.hector.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 文档解析消息配置。
 * 文档创建完成后仅发布业务标识，消费者异步读取文件并更新对应版本的解析状态。
 */
@Configuration
public class RabbitMqConfig {

    public static final String DOCUMENT_EXCHANGE = "hex.doc.document.exchange";
    public static final String DOCUMENT_PARSE_QUEUE = "hex.doc.document.parse.queue";
    public static final String DOCUMENT_PARSE_ROUTING_KEY = "document.parse.requested";

    @Bean
    public TopicExchange documentExchange() {
        return new TopicExchange(DOCUMENT_EXCHANGE, true, false);
    }

    @Bean
    public Queue documentParseQueue() {
        return new Queue(DOCUMENT_PARSE_QUEUE, true);
    }

    @Bean
    public Binding documentParseBinding(Queue documentParseQueue, TopicExchange documentExchange) {
        return BindingBuilder.bind(documentParseQueue).to(documentExchange).with(DOCUMENT_PARSE_ROUTING_KEY);
    }

    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
