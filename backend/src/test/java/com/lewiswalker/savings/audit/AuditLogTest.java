package com.lewiswalker.savings.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lewiswalker.savings.TestcontainersConfiguration;
import com.lewiswalker.savings.account.AccountCapReachedException;
import com.lewiswalker.savings.account.AccountRepository;
import com.lewiswalker.savings.account.AccountService;
import com.lewiswalker.savings.account.AccountView;
import com.lewiswalker.savings.customer.CustomerNotVerifiedException;
import com.lewiswalker.savings.security.DemoIdentities;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The audit trail, asserted rather than assumed.
 *
 * <p>A refusal that leaves no record is indistinguishable from one that never happened,
 * and "we are fairly sure we log that" is not an answer to give a regulator. These tests
 * are what turn the claim into something checkable.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)   // the real directory: Alan's CDD is pending
class AuditLogTest {

    private static final UUID ADA =
            DemoIdentities.byEmail("ada@example.test").orElseThrow().customerId();
    private static final UUID ALAN =
            DemoIdentities.byEmail("alan@example.test").orElseThrow().customerId();

    @Autowired private AccountService accounts;
    @Autowired private AccountRepository repository;

    private Logger auditLogger;
    private ListAppender<ILoggingEvent> captured;

    @BeforeEach
    void capture() {
        repository.deleteAll();
        auditLogger = (Logger) LoggerFactory.getLogger("audit");
        captured = new ListAppender<>();
        captured.start();
        auditLogger.addAppender(captured);
    }

    @AfterEach
    void release() {
        auditLogger.detachAppender(captured);
    }

    @Test
    @DisplayName("an opening that is refused for incomplete due diligence is recorded, with the reason")
    void refusalForDueDiligenceIsAudited() {
        assertThatThrownBy(() -> accounts.open(ALAN, null))
                .isInstanceOf(CustomerNotVerifiedException.class);

        assertThat(lines()).anySatisfy(line -> {
            assertThat(line).contains("event=account.refused");
            assertThat(line).contains("customer=" + ALAN);
            // A stable code, not a sentence: anything counting or alerting on this
            // must not break when somebody rewords a message.
            assertThat(line).contains("reason=due-diligence-pending");
        });
    }

    @Test
    @DisplayName("the reason is in the audit record even though the API withholds it from the caller")
    void reasonIsAuditedButNotDisclosed() {
        assertThatThrownBy(() -> accounts.open(ALAN, null))
                .isInstanceOf(CustomerNotVerifiedException.class);

        // Compliance needs to know which stage verification is at. The customer is told
        // only that they cannot open an account and to get in touch - the bank's
        // assessment of them belongs in a conversation with a person, not an error body.
        // Two audiences, two disclosures, and this asserts the internal half.
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("pending"));
    }

    @Test
    @DisplayName("a successful opening is recorded too")
    void openingIsAudited() {
        AccountView opened = accounts.open(ADA, "Holiday fund");

        assertThat(lines()).anySatisfy(line -> {
            assertThat(line).contains("event=account.opened");
            assertThat(line).contains("customer=" + ADA);
            assertThat(line).contains("account=" + opened.id());
        });
    }

    @Test
    @DisplayName("hitting the account limit is recorded")
    void capRefusalIsAudited() {
        for (int i = 0; i < AccountService.ACCOUNTS_PER_CUSTOMER; i++) {
            accounts.open(ADA, null);
        }

        assertThatThrownBy(() -> accounts.open(ADA, null))
                .isInstanceOf(AccountCapReachedException.class);

        assertThat(lines()).anySatisfy(line -> {
            assertThat(line).contains("event=account.refused");
            assertThat(line).contains("reason=account-limit-reached");
        });
    }

    @Test
    @DisplayName("the audit trail carries identifiers and outcomes, never customer data")
    void auditRecordsCarryNoPersonalData() {
        accounts.open(ADA, "Holiday fund");
        assertThatThrownBy(() -> accounts.open(ALAN, "Rainy day")).isInstanceOf(RuntimeException.class);

        assertThat(lines()).isNotEmpty();
        for (String line : lines()) {
            // An audit record says who did what and what the answer was. An opaque id
            // says "who" perfectly well and means nothing to anyone reading the log
            // without database access.
            assertThat(line).doesNotContain("Ada Lovelace");
            assertThat(line).doesNotContain("Alan Turing");
            assertThat(line).doesNotContain("Holiday fund");
            assertThat(line).doesNotContain("Rainy day");
            assertThat(line).doesNotContain("@example.test");
            assertThat(line).doesNotContain("99-0001-");
        }
    }

    private List<String> lines() {
        return captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
