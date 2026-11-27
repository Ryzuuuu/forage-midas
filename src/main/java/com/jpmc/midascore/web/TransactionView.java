package com.jpmc.midascore.web;

import com.jpmc.midascore.entity.RejectionReason;
import com.jpmc.midascore.entity.TransactionRecord;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionView(long id, long senderId, long recipientId, BigDecimal amount, BigDecimal incentive,
                              TransactionRecord.Status status, RejectionReason rejectionReason, Instant processedAt) {

    static TransactionView of(TransactionRecord record) {
        return new TransactionView(record.getId(), record.getRequestedSenderId(), record.getRequestedRecipientId(),
                record.getAmount(), record.getIncentive(), record.getStatus(), record.getRejectionReason(),
                record.getProcessedAt());
    }
}
