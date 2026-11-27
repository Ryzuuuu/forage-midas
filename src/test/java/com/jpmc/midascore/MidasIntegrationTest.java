package com.jpmc.midascore;

import com.jpmc.midascore.entity.TransactionRecord;
import com.jpmc.midascore.entity.UserRecord;
import com.jpmc.midascore.foundation.Balance;
import com.jpmc.midascore.foundation.Transaction;
import com.jpmc.midascore.incentive.IncentiveClient;
import com.jpmc.midascore.repository.TransactionRecordRepository;
import com.jpmc.midascore.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Messages on a real (embedded) Kafka broker, through the listener, into H2, and out of the REST API. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "general.kafka-topic=midas-it"})
@EmbeddedKafka(partitions = 1, topics = "midas-it")
@DirtiesContext
class MidasIntegrationTest {
    @Autowired
    private KafkaTemplate<String, Transaction> kafka;
    @Autowired
    private UserRepository users;
    @Autowired
    private TransactionRecordRepository transactions;
    @Autowired
    private TestRestTemplate rest;
    @MockBean
    private IncentiveClient incentiveClient;
    @Value("${general.kafka-topic}")
    private String topic;

    @Test
    void transactionsFlowFromKafkaToTheBalanceApi() {
        when(incentiveClient.fetchIncentive(any())).thenReturn(new BigDecimal("1.50"));
        UserRecord sender = users.save(new UserRecord("sender", new BigDecimal("100.00")));
        UserRecord recipient = users.save(new UserRecord("recipient", new BigDecimal("0.00")));

        kafka.send(topic, new Transaction(sender.getId(), recipient.getId(), 40f));
        kafka.send(topic, new Transaction(sender.getId(), recipient.getId(), 70f));   // overdraws: rejected
        kafka.send(topic, new Transaction(sender.getId(), recipient.getId(), 60f));

        await().atMost(Duration.ofSeconds(20)).until(() -> transactions.count() == 3);

        assertThat(rest.getForObject("/balance?userId=" + sender.getId(), Balance.class).getAmount()).isEqualTo(0f);
        assertThat(rest.getForObject("/balance?userId=" + recipient.getId(), Balance.class).getAmount()).isEqualTo(103f);
        assertThat(transactions.findAll()).extracting(TransactionRecord::getStatus).containsExactlyInAnyOrder(
                TransactionRecord.Status.ACCEPTED, TransactionRecord.Status.REJECTED, TransactionRecord.Status.ACCEPTED);
    }
}
