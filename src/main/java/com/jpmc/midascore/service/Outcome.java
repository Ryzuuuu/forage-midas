package com.jpmc.midascore.service;

public enum Outcome {
    ACCEPTED,
    REJECTED,
    /** The same Kafka message was already processed; nothing changed. */
    DUPLICATE
}
