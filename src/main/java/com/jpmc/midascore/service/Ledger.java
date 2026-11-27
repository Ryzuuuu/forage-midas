package com.jpmc.midascore.service;

import com.jpmc.midascore.entity.MessageSource;
import com.jpmc.midascore.entity.RejectionReason;
import com.jpmc.midascore.entity.TransactionRecord;
import com.jpmc.midascore.entity.UserRecord;
import com.jpmc.midascore.repository.TransactionRecordRepository;
import com.jpmc.midascore.repository.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * The only code that moves money. Each call is one database transaction: balances and the audit
 * row commit together or not at all.
 */
@Component
public class Ledger {
    private final UserRepository users;
    private final TransactionRecordRepository transactions;

    public Ledger(UserRepository users, TransactionRecordRepository transactions) {
        this.users = users;
        this.transactions = transactions;
    }

    /**
     * Locks both accounts, re-checks the balance under the lock and applies the transfer.
     * Rows are always locked lowest id first, so two opposite transfers between the same pair of
     * accounts cannot deadlock.
     */
    @Transactional
    public Outcome transfer(MessageSource source, long senderId, long recipientId,
                            BigDecimal amount, BigDecimal incentive) {
        UserRecord first = users.findByIdForUpdate(Math.min(senderId, recipientId)).orElse(null);
        UserRecord second = users.findByIdForUpdate(Math.max(senderId, recipientId)).orElse(null);
        UserRecord sender = senderId <= recipientId ? first : second;
        UserRecord recipient = senderId <= recipientId ? second : first;

        if (sender == null) {
            return reject(source, senderId, recipientId, amount, RejectionReason.UNKNOWN_SENDER);
        }
        if (recipient == null) {
            return reject(source, senderId, recipientId, amount, RejectionReason.UNKNOWN_RECIPIENT);
        }
        if (sender.getBalance().compareTo(amount) < 0) {
            return reject(source, senderId, recipientId, amount, RejectionReason.INSUFFICIENT_FUNDS);
        }

        sender.debit(amount);
        recipient.credit(amount.add(incentive));
        transactions.saveAndFlush(TransactionRecord.accepted(source, sender, recipient, amount, incentive));
        return Outcome.ACCEPTED;
    }

    @Transactional
    public Outcome reject(MessageSource source, long senderId, long recipientId,
                          BigDecimal amount, RejectionReason reason) {
        transactions.saveAndFlush(TransactionRecord.rejected(source, senderId, recipientId, amount, reason));
        return Outcome.REJECTED;
    }
}
