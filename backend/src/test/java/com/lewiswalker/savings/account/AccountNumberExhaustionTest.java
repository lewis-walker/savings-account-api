package com.lewiswalker.savings.account;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Running out of account numbers must not be an unhandled 500.
 *
 * <p>It is an operational event — the branch's registered range is used up and a new one
 * is needed — so it belongs in the same bucket as "the allocator could not answer", which
 * maps to a 503 and is logged for somebody to act on. Before this it escaped as a bare
 * {@code IllegalStateException}, which no handler claimed.
 */
class AccountNumberExhaustionTest {

    @Test
    @DisplayName("an exhausted range surfaces as an allocation failure, not an unhandled error")
    void exhaustionBecomesAnAllocationFailure() {
        AccountRepository repository = mock(AccountRepository.class);
        // Past MAX_BODY_EXCLUSIVE, so the format refuses rather than wrapping.
        when(repository.nextAccountNumberSeed()).thenReturn(99_000L);

        LocalSequenceAccountNumberAllocator allocator =
                new LocalSequenceAccountNumberAllocator(repository, new NzAccountNumberFormat());

        assertThatThrownBy(() -> allocator.allocate(UUID.randomUUID(), "ref-00000001"))
                .isInstanceOf(AccountNumberAllocationException.class)
                .hasMessageContaining("exhausted");
    }
}
