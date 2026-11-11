package com.athletex.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class AccountEntity {

    /** System accounts created by the V1 migration. */
    public static final UUID HOUSE_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID SETTLEMENT_POOL_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Id
    private UUID id;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    @Column(name = "balance_cents", nullable = false)
    private long balanceCents;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AccountEntity() {
    }

    private AccountEntity(UUID id, UUID ownerUserId, AccountType type) {
        this.id = id;
        this.ownerUserId = ownerUserId;
        this.type = type;
        this.balanceCents = 0L;
        this.createdAt = Instant.now();
    }

    public static AccountEntity userAccount(UUID ownerUserId, AccountType type) {
        if (!type.mustStayNonNegative()) {
            throw new IllegalArgumentException(type + " is not a user account type");
        }
        return new AccountEntity(UUID.randomUUID(), ownerUserId, type);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public AccountType getType() {
        return type;
    }

    public long getBalanceCents() {
        return balanceCents;
    }

    void setBalanceCents(long balanceCents) {
        this.balanceCents = balanceCents;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
