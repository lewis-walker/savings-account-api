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
     *
     * <p>Advisory only. Under READ COMMITTED two concurrent callers both see the same
     * committed rows and both get the same answer — that is expected, and the unique
     * index on (customer_id, sequence_no) is what resolves it. This query picks a
     * likely-free slot; the database decides who actually gets it.
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
