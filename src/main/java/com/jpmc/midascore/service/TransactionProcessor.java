package com.jpmc.midascore.service;

import com.jpmc.midascore.entity.MessageSource;
import com.jpmc.midascore.entity.RejectionReason;
import com.jpmc.midascore.entity.UserRecord;
import com.jpmc.midascore.foundation.Transaction;
import com.jpmc.midascore.incentive.IncentiveClient;
import com.jpmc.midascore.repository.TransactionRecordRepository;
import com.jpmc.midascore.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Validates a transaction, fetches its incentive and hands it to the {@link Ledger}.
 *
 * <p>The incentive API is called before any row is locked, so a slow network call never holds a
 * database lock. The balance is checked again under the lock in {@link Ledger#transfer}.
 */
@Service
public class TransactionProcessor {
    private static final Logger log = LoggerFactory.getLogger(TransactionProcessor.class);

    private final UserRepository users;
    private final TransactionRecordRepository transactions;
    private final IncentiveClient incentiveClient;
    private final Ledger ledger;

    public TransactionProcessor(UserRepository users, TransactionRecordRepository transactions,
                                IncentiveClient incentiveClient, Ledger ledger) {
        this.users = users;
        this.transactions = transactions;
        this.incentiveClient = incentiveClient;
        this.ledger = ledger;
    }

    public Outcome process(Transaction transaction, MessageSource source) {
        if (transactions.existsBySourceTopicAndSourcePartitionAndSourceOffset(
                source.topic(), source.partition(), source.offset())) {
            log.info("Skipping redelivered message {}", source);
            return Outcome.DUPLICATE;
        }

        BigDecimal amount = toMoney(transaction.getAmount());
        long senderId = transaction.getSenderId();
        long recipientId = transaction.getRecipientId();

        try {
            Optional<RejectionReason> problem = validate(senderId, recipientId, amount);
            if (problem.isPresent()) {
                log.info("Rejected {} from {}: {}", transaction, source, problem.get());
                return ledger.reject(source, senderId, recipientId, amount, problem.get());
            }
            BigDecimal incentive = incentiveClient.fetchIncentive(transaction);
            Outcome outcome = ledger.transfer(source, senderId, recipientId, amount, incentive);
            log.info("{} {} from {} (incentive {})", outcome, transaction, source, incentive);
            return outcome;
        } catch (DataIntegrityViolationException e) {
            // Another consumer committed the same message between our check and our insert.
            log.info("Message {} was processed concurrently, skipping", source);
            return Outcome.DUPLICATE;
        }
    }

    /** Cheap checks without locks. The balance is checked again under the lock before money moves. */
    private Optional<RejectionReason> validate(long senderId, long recipientId, BigDecimal amount) {
        if (amount.signum() <= 0) {
            return Optional.of(RejectionReason.NON_POSITIVE_AMOUNT);
        }
        if (senderId == recipientId) {
            return Optional.of(RejectionReason.SAME_ACCOUNT);
        }
        UserRecord sender = users.findById(senderId);
        if (sender == null) {
            return Optional.of(RejectionReason.UNKNOWN_SENDER);
        }
        if (users.findById(recipientId) == null) {
            return Optional.of(RejectionReason.UNKNOWN_RECIPIENT);
        }
        if (sender.getBalance().compareTo(amount) < 0) {
            return Optional.of(RejectionReason.INSUFFICIENT_FUNDS);
        }
        return Optional.empty();
    }

    private static BigDecimal toMoney(float amount) {
        if (!Float.isFinite(amount)) {
            return BigDecimal.ZERO.setScale(UserRecord.SCALE);
        }
        return new BigDecimal(Float.toString(amount)).setScale(UserRecord.SCALE, RoundingMode.HALF_EVEN);
    }
}
