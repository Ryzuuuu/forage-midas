package com.jpmc.midascore.service;

import com.jpmc.midascore.entity.MessageSource;
import com.jpmc.midascore.entity.RejectionReason;
import com.jpmc.midascore.entity.TransactionRecord;
import com.jpmc.midascore.entity.UserRecord;
import com.jpmc.midascore.foundation.Transaction;
import com.jpmc.midascore.incentive.IncentiveClient;
import com.jpmc.midascore.incentive.IncentiveUnavailableException;
import com.jpmc.midascore.repository.TransactionRecordRepository;
import com.jpmc.midascore.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
class TransactionProcessorTest {
    private static final AtomicLong offsets = new AtomicLong();

    @Autowired
    private TransactionProcessor processor;
    @Autowired
    private UserRepository users;
    @Autowired
    private TransactionRecordRepository transactions;
    @MockBean
    private IncentiveClient incentiveClient;

    private UserRecord alice;
    private UserRecord bob;

    @BeforeEach
    void setUp() {
        transactions.deleteAll();
        users.deleteAll();
        alice = users.save(new UserRecord("alice", new BigDecimal("100.00")));
        bob = users.save(new UserRecord("bob", new BigDecimal("50.00")));
        when(incentiveClient.fetchIncentive(any())).thenReturn(new BigDecimal("0.00"));
    }

    @Test
    void validTransferMovesMoneyAndCreditsIncentiveToRecipientOnly() {
        when(incentiveClient.fetchIncentive(any())).thenReturn(new BigDecimal("4.20"));

        Outcome outcome = processor.process(new Transaction(alice.getId(), bob.getId(), 30.10f), nextSource());

        assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
        assertThat(balanceOf(alice)).isEqualByComparingTo("69.90");
        assertThat(balanceOf(bob)).isEqualByComparingTo("84.30");
        TransactionRecord record = transactions.findAll().get(0);
        assertThat(record.getStatus()).isEqualTo(TransactionRecord.Status.ACCEPTED);
        assertThat(record.getAmount()).isEqualByComparingTo("30.10");
        assertThat(record.getIncentive()).isEqualByComparingTo("4.20");
    }

    @Test
    void senderMayTransferExactlyTheirWholeBalance() {
        assertThat(processor.process(new Transaction(alice.getId(), bob.getId(), 100f), nextSource()))
                .isEqualTo(Outcome.ACCEPTED);
        assertThat(balanceOf(alice)).isEqualByComparingTo("0.00");
    }

    @ParameterizedTest(name = "{3}")
    @CsvSource({
            "999, 2, 10, UNKNOWN_SENDER",
            "1, 999, 10, UNKNOWN_RECIPIENT",
            "1, 1, 10, SAME_ACCOUNT",
            "1, 2, 0, NON_POSITIVE_AMOUNT",
            "1, 2, -5, NON_POSITIVE_AMOUNT",
            "1, 2, 100.01, INSUFFICIENT_FUNDS"
    })
    void invalidTransactionsAreRecordedAsRejectedAndChangeNoBalance(long sender, long recipient, float amount,
                                                                     RejectionReason reason) {
        long senderId = sender == 1 ? alice.getId() : sender == 2 ? bob.getId() : sender;
        long recipientId = recipient == 1 ? alice.getId() : recipient == 2 ? bob.getId() : recipient;

        Outcome outcome = processor.process(new Transaction(senderId, recipientId, amount), nextSource());

        assertThat(outcome).isEqualTo(Outcome.REJECTED);
        assertThat(transactions.findAll()).singleElement()
                .satisfies(r -> assertThat(r.getRejectionReason()).isEqualTo(reason));
        assertThat(balanceOf(alice)).isEqualByComparingTo("100.00");
        assertThat(balanceOf(bob)).isEqualByComparingTo("50.00");
        verifyNoInteractions(incentiveClient);
    }

    @Test
    void redeliveredMessageIsAppliedOnlyOnce() {
        MessageSource source = nextSource();
        Transaction transaction = new Transaction(alice.getId(), bob.getId(), 10f);

        assertThat(processor.process(transaction, source)).isEqualTo(Outcome.ACCEPTED);
        assertThat(processor.process(transaction, source)).isEqualTo(Outcome.DUPLICATE);

        assertThat(balanceOf(alice)).isEqualByComparingTo("90.00");
        assertThat(transactions.count()).isEqualTo(1);
        verify(incentiveClient, times(1)).fetchIncentive(any());
    }

    @Test
    void incentiveOutageLeavesNoTraceSoTheMessageCanBeRetried() {
        MessageSource source = nextSource();
        Transaction transaction = new Transaction(alice.getId(), bob.getId(), 10f);
        when(incentiveClient.fetchIncentive(any()))
                .thenThrow(new IncentiveUnavailableException("down", null))
                .thenReturn(new BigDecimal("1.00"));

        assertThatThrownBy(() -> processor.process(transaction, source))
                .isInstanceOf(IncentiveUnavailableException.class);
        assertThat(transactions.count()).isZero();
        assertThat(balanceOf(alice)).isEqualByComparingTo("100.00");

        assertThat(processor.process(transaction, source)).isEqualTo(Outcome.ACCEPTED);
        assertThat(balanceOf(bob)).isEqualByComparingTo("61.00");
    }

    @Test
    void concurrentTransfersNeverOverdrawOrLoseAnUpdate() throws Exception {
        UserRecord payer = users.save(new UserRecord("payer", new BigDecimal("1000.00")));
        UserRecord payee = users.save(new UserRecord("payee", new BigDecimal("0.00")));
        int threads = 8;
        int transfersPerThread = 25;   // 200 transfers of 10.00 against a balance of 1000.00
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Outcome>>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                List<Outcome> outcomes = new ArrayList<>();
                for (int i = 0; i < transfersPerThread; i++) {
                    outcomes.add(processor.process(new Transaction(payer.getId(), payee.getId(), 10f), nextSource()));
                }
                return outcomes;
            }));
        }
        start.countDown();
        List<Outcome> all = new ArrayList<>();
        for (Future<List<Outcome>> future : futures) {
            all.addAll(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(all).filteredOn(o -> o == Outcome.ACCEPTED).hasSize(100);
        assertThat(all).filteredOn(o -> o == Outcome.REJECTED).hasSize(100);
        assertThat(balanceOf(payer)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(payee)).isEqualByComparingTo("1000.00");
    }

    private BigDecimal balanceOf(UserRecord user) {
        return users.findById((long) user.getId()).getBalance();
    }

    private static MessageSource nextSource() {
        return new MessageSource("test-topic", 0, offsets.incrementAndGet());
    }
}
