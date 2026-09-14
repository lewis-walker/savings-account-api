package com.lewiswalker.savings.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lewiswalker.savings.TestcontainersConfiguration;
import com.lewiswalker.savings.support.StubCustomerService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The cap counts a customer's <em>open</em> accounts.
 *
 * <p>Closing an account is not a feature of this service, so these tests close one with
 * SQL — the statement a close endpoint would eventually run. The point is not to
 * pre-build that endpoint. It is that the rule "five accounts per customer" is written
 * against a column, so the day an account can leave the set, the cap keeps counting
 * correctly instead of needing a second design.
 *
 * <p>The last two tests bypass {@link AccountService} entirely and write rows directly,
 * because they are about the database rather than the code: if the invariant only holds
 * when every writer remembers to ask for a free slot first, it is not an invariant.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, StubCustomerService.class})
class AccountSlotReuseTest {

    private static final int CAP = AccountService.ACCOUNTS_PER_CUSTOMER;

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clear() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("closing an account returns its slot to the customer")
    void closingAnAccountReturnsItsSlot() {
        UUID customerId = UUID.randomUUID();
        openFully(customerId);
        assertThatThrownBy(() -> accountService.open(customerId, null))
                .isInstanceOf(AccountCapReachedException.class);

        close(customerId, 3);

        AccountView reopened = accountService.open(customerId, null);

        assertThat(reopened.slotNo())
                .as("the freed slot, not a sixth one")
                .isEqualTo((short) 3);
        assertThat(openCount(customerId)).isEqualTo(CAP);
        assertThat(repository.findByCustomerIdOrderBySlotNo(customerId))
                .as("the closed account is still on file")
                .hasSize(CAP + 1);
    }

    @Test
    @DisplayName("the lowest free slot is the one taken")
    void theLowestFreeSlotIsTaken() {
        UUID customerId = UUID.randomUUID();
        openFully(customerId);

        close(customerId, 4);
        close(customerId, 2);

        assertThat(accountService.open(customerId, null).slotNo()).isEqualTo((short) 2);
        assertThat(accountService.open(customerId, null).slotNo()).isEqualTo((short) 4);
    }

    @Test
    @DisplayName("closed accounts never count towards the cap, however many there are")
    void closedAccountsDoNotCountTowardsTheCap() {
        UUID customerId = UUID.randomUUID();

        openFully(customerId);
        for (int slot = 1; slot <= CAP; slot++) {
            close(customerId, slot);
        }
        openFully(customerId);

        assertThatThrownBy(() -> accountService.open(customerId, null))
                .as("full again on the second set, not the tenth account")
                .isInstanceOf(AccountCapReachedException.class);

        assertThat(openCount(customerId)).isEqualTo(CAP);
        assertThat(repository.findByCustomerIdOrderBySlotNo(customerId)).hasSize(CAP * 2);
    }

    @Test
    @DisplayName("the database refuses a second open account in one slot")
    void theDatabaseRefusesASecondOpenAccountInOneSlot() {
        UUID customerId = UUID.randomUUID();
        short slot = accountService.open(customerId, null).slotNo();

        assertThatThrownBy(() -> insertDirectly(customerId, slot, "OPEN"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("account_open_slot_uq");
    }

    @Test
    @DisplayName("the database leaves a closed account's slot free")
    void theDatabaseLeavesAClosedAccountsSlotFree() {
        UUID customerId = UUID.randomUUID();
        short slot = accountService.open(customerId, null).slotNo();
        close(customerId, slot);

        insertDirectly(customerId, slot, "OPEN");

        assertThat(openCount(customerId))
                .as("the index scopes itself to open accounts, so the slot was available")
                .isEqualTo(1);
    }

    private void openFully(UUID customerId) {
        for (int i = 0; i < CAP; i++) {
            accountService.open(customerId, null);
        }
    }

    /** What a close endpoint would run. Asserts it hit something, so a stale test fails loudly. */
    private void close(UUID customerId, int slotNo) {
        int closed = jdbc.update("""
                update account set status = 'CLOSED'
                 where customer_id = ? and slot_no = ? and status = 'OPEN'
                """, customerId, slotNo);
        assertThat(closed).as("an open account in slot %d to close", slotNo).isEqualTo(1);
    }

    /** Straight to the table, so the constraint answers rather than the finder. */
    private void insertDirectly(UUID customerId, short slotNo, String status) {
        jdbc.update("""
                insert into account (id, account_number, customer_id, customer_name,
                                     slot_no, status, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), "99-0000-" + System.nanoTime() % 10_000_000L + "-000",
                customerId, StubCustomerService.ANY_CUSTOMER_NAME,
                slotNo, status, java.sql.Timestamp.from(Instant.now()));
    }

    private long openCount(UUID customerId) {
        return jdbc.queryForObject(
                "select count(*) from account where customer_id = ? and status = 'OPEN'",
                Long.class, customerId);
    }
}
