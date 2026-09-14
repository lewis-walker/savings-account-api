package com.lewiswalker.savings.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A savings account.
 *
 * <p>Append-only in this scope: nothing updates an account, so there is no optimistic
 * locking, no updated_at and no update path.
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

    /**
     * Which of the customer's five slots this account holds, for as long as it is
     * {@link AccountStatus#OPEN}. The database enforces both halves of that; see
     * V1__account.sql.
     *
     * <p>A slot, not a running count: it says where the account sits among the
     * customer's open accounts, not how many they have ever had.
     */
    @Column(name = "slot_no", nullable = false, updatable = false)
    private short slotNo;

    /**
     * Stored as the enum name, so the column reads the same as the code and the
     * partial index's {@code where status = 'OPEN'} matches it.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
        // for JPA
    }

    /** Opens at {@link AccountStatus#OPEN}, which is the only status this service assigns. */
    public Account(UUID id, String accountNumber, UUID customerId, String customerName,
                   String nickname, short slotNo, Instant now) {
        this.id = id;
        this.accountNumber = accountNumber;
        this.customerId = customerId;
        this.customerName = customerName;
        this.nickname = nickname;
        this.slotNo = slotNo;
        this.status = AccountStatus.OPEN;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public String getAccountNumber() { return accountNumber; }
    public UUID getCustomerId() { return customerId; }
    public String getCustomerName() { return customerName; }
    public String getNickname() { return nickname; }
    public short getSlotNo() { return slotNo; }
    public AccountStatus getStatus() { return status; }
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
        return "Account[id=%s, slotNo=%d]".formatted(id, slotNo);
    }
}
