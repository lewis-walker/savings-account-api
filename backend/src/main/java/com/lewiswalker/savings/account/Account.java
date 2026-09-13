package com.lewiswalker.savings.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A savings account.
 *
 * <p>No Lombok here, on purpose. Lombok's generated {@code equals}/{@code hashCode}
 * touch every field, which on a JPA entity drags lazy associations into existence at
 * surprising moments, and its {@code toString} does the same. Identity on an entity
 * should be the identifier and nothing else, which is what this does.
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_number", nullable = false, updatable = false, length = 32)
    private String accountNumber;

    /** From the JWT {@code sub} claim. Never accepted from a request body. */
    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "customer_name", nullable = false, length = 140)
    private String customerName;

    @Column(length = 30)
    private String nickname;

    /** 1..5 within a customer. The database caps the series; see V1__account.sql. */
    @Column(name = "sequence_no", nullable = false, updatable = false)
    private short sequenceNo;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
        this.updatedAt = now;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getAccountNumber() { return accountNumber; }
    public UUID getCustomerId() { return customerId; }
    public String getCustomerName() { return customerName; }
    public String getNickname() { return nickname; }
    public short getSequenceNo() { return sequenceNo; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void rename(String nickname) {
        this.nickname = nickname;
    }

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

    /**
     * Identifiers only — never attributes.
     *
     * <p>An earlier version included the account number, which put it into any log line
     * that interpolated an Account. That is CWE-532, and in a bank it is a finding, not
     * a style note. The id is an opaque UUID that means nothing without database
     * access; the account number, the customer name and the nickname are all things a
     * log reader should not be handed.
     */
    @Override
    public String toString() {
        return "Account[id=%s, sequenceNo=%d]".formatted(id, sequenceNo);
    }
}
