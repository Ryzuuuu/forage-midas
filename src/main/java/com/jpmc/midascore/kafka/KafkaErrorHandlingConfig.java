package com.jpmc.midascore.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

/**
 * A failed message (for example, the incentive API is down) is retried with a fixed back off.
 * If it still fails, it is logged with its exact topic, partition and offset and the consumer
 * moves on, so one bad message cannot block the partition. Malformed messages are not retried.
 */
@Configuration
public class KafkaErrorHandlingConfig {
    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlingConfig.class);

    @Bean
    public CommonErrorHandler kafkaErrorHandler(@Value("${midas.consumer.retry-interval}") Duration interval,
                                                @Value("${midas.consumer.max-retries}") long maxRetries) {
        return new DefaultErrorHandler(
                (record, exception) -> log.error("Giving up on message {}-{}@{}: {}",
                        record.topic(), record.partition(), record.offset(), exception.getMessage()),
                new FixedBackOff(interval.toMillis(), maxRetries));
    }
}
