package com.lewiswalker.savings.account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findByCustomerIdOrderBySlotNo(UUID customerId);

    /**
     * The lowest slot in 1..cap that no open account of this customer holds, or empty
     * when they hold one in every slot.
     *
     * <p>Advisory, not authoritative. It reads committed rows, so a slot it offers may
     * be taken by a request that has not committed yet; {@code account_open_slot_uq}
     * settles that, and the caller retries. Its job is to propose a slot and to answer
     * "full" without paying for an account number first.
     *
     * <p>The {@code status = 'OPEN'} predicate is the one in that index, spelled the
     * same way on purpose: it is what makes this an index lookup per candidate, and it
     * keeps one definition of "occupies a slot" rather than two that can drift.
     */
    @Query(value = """
            select candidate.slot_no::smallint
              from generate_series(1, :cap) as candidate(slot_no)
             where not exists (
                       select 1
                         from account a
                        where a.customer_id = :customerId
                          and a.status = 'OPEN'
                          and a.slot_no = candidate.slot_no)
             order by candidate.slot_no
             limit 1
            """, nativeQuery = true)
    Optional<Short> nextFreeSlot(@Param("customerId") UUID customerId, @Param("cap") int cap);

    @Query(value = "select nextval('account_number_seq')", nativeQuery = true)
    long nextAccountNumberSeed();
}
