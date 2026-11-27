package com.jpmc.midascore.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row per Kafka message, accepted or rejected, so every decision is auditable.
 * The (topic, partition, offset) triple is unique: a message that Kafka redelivers
 * after a crash or rebalance is recognised and never applied twice.
 */
@Entity
@Table(
        uniqueConstraints = @UniqueConstraint(name = "uk_transaction_source",
                columnNames = {"source_topic", "source_partition", "source_offset"}),
        indexes = {
                @Index(name = "ix_transaction_sender", columnList = "sender_id"),
                @Index(name = "ix_transaction_recipient", columnList = "recipient_id")
        })
public class TransactionRecord {

    public enum Status { ACCEPTED, REJECTED }

    @Id
    @GeneratedValue
    private long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id")
    private UserRecord sender;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id")
    private UserRecord recipient;

    /** Ids as they arrived on the message, kept even when they match no user. */
    @Column(nullable = false)
    private long requestedSenderId;

    @Column(nullable = false)
    private long requestedRecipientId;

    @Column(nullable = false, precision = 19, scale = UserRecord.SCALE)
    private BigDecimal amount;

    @Column(nullable = false, precision = 19, scale = UserRecord.SCALE)
    private BigDecimal incentive;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private RejectionReason rejectionReason;

    @Column(name = "source_topic", nullable = false)
    private String sourceTopic;

    @Column(name = "source_partition", nullable = false)
    private int sourcePartition;

    @Column(name = "source_offset", nullable = false)
    private long sourceOffset;

    @Column(nullable = false)
    private Instant processedAt;

    protected TransactionRecord() {
    }

    private TransactionRecord(MessageSource source, long requestedSenderId, long requestedRecipientId,
                              BigDecimal amount, BigDecimal incentive, Status status, RejectionReason reason,
                              UserRecord sender, UserRecord recipient) {
        this.sourceTopic = source.topic();
        this.sourcePartition = source.partition();
        this.sourceOffset = source.offset();
        this.requestedSenderId = requestedSenderId;
        this.requestedRecipientId = requestedRecipientId;
        this.amount = amount;
        this.incentive = incentive;
        this.status = status;
        this.rejectionReason = reason;
        this.sender = sender;
        this.recipient = recipient;
        this.processedAt = Instant.now();
    }

    public static TransactionRecord accepted(MessageSource source, UserRecord sender, UserRecord recipient,
                                             BigDecimal amount, BigDecimal incentive) {
        return new TransactionRecord(source, sender.getId(), recipient.getId(), amount, incentive,
                Status.ACCEPTED, null, sender, recipient);
    }

    public static TransactionRecord rejected(MessageSource source, long senderId, long recipientId,
                                             BigDecimal amount, RejectionReason reason) {
        return new TransactionRecord(source, senderId, recipientId, amount, BigDecimal.ZERO.setScale(UserRecord.SCALE),
                Status.REJECTED, reason, null, null);
    }

    public long getId() {
        return id;
    }

    public long getRequestedSenderId() {
        return requestedSenderId;
    }

    public long getRequestedRecipientId() {
        return requestedRecipientId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getIncentive() {
        return incentive;
    }

    public Status getStatus() {
        return status;
    }

    public RejectionReason getRejectionReason() {
        return rejectionReason;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
