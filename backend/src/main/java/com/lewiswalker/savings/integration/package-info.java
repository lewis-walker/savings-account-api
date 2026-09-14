/**
 * Systems a bank owns somewhere else.
 *
 * <p>Each package here is an interface the application depends on and a stand-in that
 * satisfies it locally, so the whole thing runs from one command. The stand-ins are the
 * demo; the interfaces are the design. What each real one would need — a timeout, a
 * circuit breaker, reconciliation for a response that was lost rather than refused — is
 * in DECISIONS.md rather than half-built here.
 *
 * <p>Resilience is shown once, on the customer lookup, where {@code RetryPolicyTest}
 * proves the policy retries what is transient and refuses to retry what is not. Repeating
 * the annotation on every stand-in would be tuning parameters copied between classes that
 * cannot fail.
 */
package com.lewiswalker.savings.integration;
