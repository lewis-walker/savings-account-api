# Design decisions

This document records assumptions, implementation decisions, and production considerations beyond the assignment’s scope.

## Assumptions

- **Customer identity comes from the access token.** The API does not accept a customer ID in the request body, preventing callers from opening accounts for other customers.
- **Customer names come from the Customers API.** Account opening assumes an existing, verified customer record under AML/CFT requirements. The account stores the name as a snapshot for audit purposes; the customer record remains authoritative for the current name. Names are excluded from tokens to avoid exposing them through request-header logging.
- **Authentication belongs to an external identity provider.** `SecurityConfig` configures the service as a resource server. `TokenController` implements the OAuth 2.0 password grant solely for the demo. This grant is removed in OAuth 2.1, and the controller would be removed in production.
- **The service owns one table: accounts.** Customer records and credentials belong to other services.

## Five-account limit

A count followed by an insert cannot enforce the limit under concurrent requests. Each account therefore has a per-customer `sequence_no`, constrained to values 1–5 and unique within that customer. Concurrent requests competing for the same slot are resolved by the database constraint.

`AccountCapConcurrencyTest` submits sixteen simultaneous requests and verifies that exactly five succeed.

Constraint handling requires three implementation details:

- Retries run outside the failed transaction because PostgreSQL aborts transactions after constraint violations.
- Each attempt opens its own transaction using a `TransactionTemplate`. Declarative `@Transactional` was not used because it is applied through a Spring proxy: a call within the same bean bypasses it silently. Programmatic demarcation makes the transaction boundary explicit at the call site.
- The violated constraint determines whether the request is retried or rejected.

## Identifiers

**Account IDs use UUIDv4.** IDs appear in URLs, so non-enumerable values reduce probing opportunities alongside authorisation checks. UUIDv7’s index-locality benefits are unnecessary at this scale and would expose a creation timestamp.

**Account numbers are sequential.** They are intended to be shared for payments and are not credentials. They use the New Zealand format, `BB-bbbb-AAAAAAA-SSS`, with a modulus-11 check validated against a published valid number. Bank code 99 is deliberately unregistered.

Sequential numbers expose account-opening volume between two observed numbers. Format-preserving encryption could conceal that sequence while preserving the number format and uniqueness; it is outside this implementation’s scope.

## Caching

Customer lookups are cached because they require a remote call on every account opening and the data changes infrequently.

Account reads are cached to satisfy the brief. The expected benefit is limited: a primary-key lookup is already served efficiently by PostgreSQL, while Redis adds a network call.

The cache implementation provides:

- **Updates after commit.** `AFTER_COMMIT` prevents publishing uncommitted data or allowing a concurrent read to repopulate an evicted entry with pre-commit data.
- **Typed serialisation.** Explicit types avoid the deserialisation risks of generic JSON default typing.
- **Failure tolerance.** Cache errors do not fail requests. `CacheOutageTest` stops Redis and verifies that the service continues to operate.
- **Separate cache models.** The cache stores `AccountView` records rather than managed JPA entities.

## Security

The service is stateless, creates no sessions, and denies access by default. Token validation checks issuer and audience as well as signature and expiry, preventing acceptance of tokens issued for other services.

Requests for another customer’s account return `404` to avoid disclosing its existence.

Signing keys are generated at startup rather than stored in the repository. This limits the demo to a single instance and invalidates tokens on restart. Production would use an HSM or KMS, `kid`-based rotation, and JWKS publication of both keys during rollover.

CSRF protection is disabled because authentication does not use cookies. This decision must be revisited if cookie-based refresh tokens are introduced.

## Resilience

Retries apply only to transient unavailability failures at the two integration ports. Permanent failures are returned immediately.

Database operations have a three-second timeout and are not retried; retries would extend request latency while occupying a thread. Sustained dependency outages require a circuit breaker. The account-slot contention loop retries immediately because another request winning a slot does not indicate dependency unavailability.

Account-allocation retries reuse the client reference. This prevents duplicate allocation when an earlier request succeeds but its response is lost.

Readiness deliberately excludes dependencies so a shared dependency outage does not remove every service instance from routing. The service can continue returning `503` responses, while `/actuator/health` reports the dependency failure to operators.

## Observability

**Correlation IDs originate at the gateway.** nginx generates them using `$request_id`, covering requests that may never reach the service. Incoming values are validated and replaced when invalid to prevent log injection (CWE-117).

**Logs contain identifiers, not customer attributes.** `LogHygieneTest` checks DEBUG output from the application, Hibernate, and Spring. It detected entity-field logging by Hibernate’s `EntityPrinter`; the `org.hibernate` logging level is pinned to prevent a root-level change from re-enabling it. This verification also supports streaming logs to the operations console.

**Audit uses a separate named logger.** This supports separate destinations and retention periods—five years for AML/CFT and seven for tax—and keeps audit logging enabled when application logging is reduced. Refusal reasons are retained for compliance but withheld from API responses for communication through customer-facing staff.

## Feature flags

A cache kill switch allows operators to bypass a failing cache without deployment. It is evaluated on every call, takes effect on the next request, and cannot fail the request if evaluation fails.

Evaluation accepts a context for future targeting. The context excludes personal data because it is sent to a third-party service.

Each flag records its purpose and expected lifetime. Temporary flags should be removed when no longer needed; operational kill switches remain.

## Front end

**Access tokens are stored in memory.** This avoids persistent storage in `localStorage`, where injected scripts could retrieve them. The trade-off is that refreshing the page signs the user out. A production implementation would use a refresh token in an `httpOnly` cookie.

**Optimistic rows use the client reference as their React key.** Server-generated account IDs are unavailable when a row is first rendered. Reconciliation merges server values into the existing row while preserving its key. Account creation does not invalidate the list, avoiding a refetch that would rebuild rows and reintroduce flicker.

**Retries reuse the idempotency key.** A retry after a timeout therefore represents the same account-opening request and cannot consume another account slot.

## Testing

Tests are selected for the failures they would detect rather than for coverage. The suite is 75 backend tests and 3 front-end tests.

**Testcontainers rather than an in-memory database.** The account limit is enforced by PostgreSQL constraints and the caching behaviour depends on Redis. A substitute would exercise different behaviour and pass regardless of whether the real constraints were correct.

**Assertions are chosen so that a plausible incorrect implementation fails.** In several cases the obvious assertion would pass against code that does nothing:

- The concurrency test releases sixteen requests simultaneously through a latch and asserts that exactly five succeed with a contiguous sequence. A sequential test passes against a count-then-insert implementation.
- The retry test counts invocations. Asserting only that the call eventually succeeds passes when no retry occurs, because the first attempt succeeds when no failure is injected. This test detected a retry proxy that was created but never applied.
- The optimistic-update test asserts that the row is the same DOM node before and after reconciliation. Asserting only the final state passes when no optimistic update occurs.
- Account-number validation is checked against a published valid New Zealand account number rather than against the implementation’s own output.

**Dependency failures are tested by removing the dependency.** `CacheOutageTest` stops the Redis container and verifies that accounts can still be opened and read. Database unavailability was verified against the running stack by stopping the PostgreSQL container.

| Test | Failure detected |
|---|---|
| `AccountCapConcurrencyTest` | An account limit implemented as a count followed by an insert. |
| `NzAccountNumberFormatTest` | An incorrect modulus-11 weight table. |
| `LogHygieneTest` | Customer data reaching logs, including from libraries. |
| `CacheOutageTest` | Redis becoming a required dependency. |
| `CacheKillSwitchTest` | A feature flag read once at startup rather than per call. |
| `RetryPolicyTest` | A retry annotation that has no effect. |
| `SecurityTest` | Private key material exposed through JWKS; user enumeration at sign-in. |
| `AuditLogTest` | A refused request leaving no audit record. |
| `optimistic.test.tsx` | A list row keyed by account ID, remounting on reconciliation. |

**Backend tests are excluded from the image build** because Testcontainers requires a Docker daemon that the build does not have. Front-end tests run during their image build, which requires only Node.

One assertion required adjustment. The check that a cache entry exists immediately after commit was intermittent and now polls briefly. Correctness does not depend on that timing, because a cache miss falls through to the committed row.

## Out of scope

| Item | Rationale or next step |
|---|---|
| Refresh-token rotation and reuse detection | Belongs to the production identity-provider integration. Existing `401` handling provides the integration point. |
| Circuit breaker | Timeouts and fail-fast behaviour are implemented; a circuit breaker is the next resilience improvement. |
| Rate limiting, ETags, CI, and load testing | Outside the assignment requirements. |
| Core banking adapter | Requires timeouts, a circuit breaker, and reconciliation for allocations whose responses are lost. |
| Durable audit events | Logger output can be lost when a container terminates. Production requires durable audit recording. |

## Production considerations

- **Account retention:** No delete endpoint is provided. Accounts should be closed and retained according to statutory requirements.
- **Account lifecycle:** Pending, active, dormant, frozen, and closed states are not modelled. New Zealand dormancy requirements would also need to be addressed.
- **Account-number allocation:** Production numbers must come from the core banking system using a range registered with Payments NZ.
- **Customer due diligence:** Account opening requires KYC checks, screening, and an approval record.
- **Operational controls:** Customer-visible changes, including feature-flag changes, require dual approval. The demo’s unauthenticated flags endpoint must be secured before production use.
