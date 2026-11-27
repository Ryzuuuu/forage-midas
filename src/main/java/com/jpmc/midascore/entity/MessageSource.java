package com.jpmc.midascore.entity;

/** Where a transaction message came from in Kafka. Unique per message, so it doubles as an idempotency key. */
public record MessageSource(String topic, int partition, long offset) {
}
