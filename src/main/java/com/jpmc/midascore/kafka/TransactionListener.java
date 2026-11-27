package com.jpmc.midascore.kafka;

import com.jpmc.midascore.entity.MessageSource;
import com.jpmc.midascore.foundation.Transaction;
import com.jpmc.midascore.service.TransactionProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransactionListener {
    private final TransactionProcessor processor;

    public TransactionListener(TransactionProcessor processor) {
        this.processor = processor;
    }

    @KafkaListener(id = "midas-transactions", topics = "${general.kafka-topic}")
    public void onTransaction(ConsumerRecord<String, Transaction> record) {
        if (record.value() == null) {
            return;
        }
        processor.process(record.value(), new MessageSource(record.topic(), record.partition(), record.offset()));
    }
}
