package com.jpmc.midascore.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Entity
public class UserRecord {

    /** Money is kept as an exact decimal with two places, never as a binary float. */
    public static final int SCALE = 2;

    @Id
    @GeneratedValue()
    private long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 19, scale = SCALE)
    private BigDecimal balance;

    @Version
    private long version;

    protected UserRecord() {
    }

    public UserRecord(String name, float balance) {
        this(name, new BigDecimal(Float.toString(balance)));
    }

    public UserRecord(String name, BigDecimal balance) {
        this.name = name;
        this.balance = balance.setScale(SCALE, RoundingMode.HALF_EVEN);
    }

    @Override
    public String toString() {
        return String.format("User[id=%d, name='%s', balance='%s']", id, name, balance.toPlainString());
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void debit(BigDecimal amount) {
        balance = balance.subtract(amount).setScale(SCALE, RoundingMode.HALF_EVEN);
    }

    public void credit(BigDecimal amount) {
        balance = balance.add(amount).setScale(SCALE, RoundingMode.HALF_EVEN);
    }
}
