package com.lewiswalker.savings.account;

/**
 * Published inside the opening transaction; delivered only if it commits.
 *
 * <p>A wrapper rather than publishing the {@link AccountView} itself, because Spring
 * routes events by type. {@code AccountView} is a general-purpose value — returned from
 * the service, held in the cache — so a listener for it would mean "interested in an
 * account" rather than "interested in an account having been opened", and would fire on
 * anything else that published one.
 */
public record AccountOpened(AccountView account) {
}
