package com.lewiswalker.savings.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A savings account.
 *
 * <p>Append-only in this scope: nothing updates an account, so there is no optimistic
 * locking, no updated_at and no update path. Amendment and closure are deliberately out
 * of scope; see DECISIONS.md.
 *
 * <p>No Lombok: its generated equals/hashCode and toString touch every field, which on a
 * JPA entity triggers lazy loading at unpredictable moments.
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_number", nullable = false, updatable = false, length = 32)
    private String accountNumber;

    /** From the token, never from a request body. */
    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "customer_name", nullable = false, length = 140)
    private String customerName;

    @Column(length = 30)
    private String nickname;

    /** 1..5 within a customer; the database enforces it. See V1__account.sql. */
    @Column(name = "sequence_no", nullable = false, updatable = false)
    private short sequenceNo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
        // for JPA
    }

    public Account(UUID id, String accountNumber, UUID customerId, String customerName,
                   String nickname, short sequenceNo, Instant now) {
        this.id = id;
        this.accountNumber = accountNumber;
        this.customerId = customerId;
        this.customerName = customerName;
        this.nickname = nickname;
        this.sequenceNo = sequenceNo;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public String getAccountNumber() { return accountNumber; }
    public UUID getCustomerId() { return customerId; }
    public String getCustomerName() { return customerName; }
    public String getNickname() { return nickname; }
    public short getSequenceNo() { return sequenceNo; }
    public Instant getCreatedAt() { return createdAt; }


    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Account account)) return false;
        return id != null && id.equals(account.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    /** Identifiers only. An account number or a name here reaches every log line (CWE-532). */
    @Override
    public String toString() {
        return "Account[id=%s, sequenceNo=%d]".formatted(id, sequenceNo);
    }
}
