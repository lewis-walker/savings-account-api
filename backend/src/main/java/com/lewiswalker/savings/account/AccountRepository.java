package com.lewiswalker.savings.account;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findByCustomerIdOrderBySequenceNo(UUID customerId);

    /**
     * Next free slot in this customer's 1..5 series.
     * Allows an optimistic check but isn't authoritative.
     * A unique index on the table resolves conflicts.
     */
    @Query(value = """
            select coalesce(max(sequence_no), 0) + 1
              from account
             where customer_id = :customerId
            """, nativeQuery = true)
    short nextSequenceNo(@Param("customerId") UUID customerId);

    @Query(value = "select nextval('account_number_seq')", nativeQuery = true)
    long nextAccountNumberSeed();
}
