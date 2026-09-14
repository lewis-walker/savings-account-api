# Design decisions

This document records assumptions, implementation decisions, and production considerations beyond the assignment’s scope.

## Assumptions

- **Customer identity comes from the access token.** The API does not accept a customer ID in the request body, preventing callers from opening accounts for other customers.
- **Customer names come from the Customers API.** Account opening assumes an existing, verified customer record under AML/CFT requirements. The account stores the name as a snapshot for audit purposes; the customer record remains authoritative for the current name. Names are excluded from tokens to avoid exposing them through request-header logging.
- **The resource server requires a subject it can read as a customer id.** Customer identity is the `sub` claim, and nothing in Spring Security's default validators requires `sub` to be present, let alone to be a customer id — a correctly signed token from the trusted issuer, with the right audience, can carry an opaque subject or none at all, which is what most identity providers issue. `JwtKeys` validates it during decoding, so such a token is a `401` rather than a request that fails somewhere further in. `SecurityTest` mints tokens that differ from a real one only in the subject, and fails if the check is removed.

**Authentication belongs to an external identity provider.** The token reaches this service from the enterprise IdP by way of the API gateway, which is where single sign-on integrates. The service validates it anyway rather than trusting an upstream header: anything that can reach the port can forge a header, so signature, issuer, audience and subject are checked here regardless of what the gateway already did. `SecurityConfig` and the decoder in `JwtKeys` are that half, and are the part that survives.

`TokenController` is not. It exists so the front end has a sign-in screen to fill in, and so the endpoint is not an open token dispenser. The password is one fixed string shared by every demo identity, published in the README - not a credential, not stored, and not hashed, because a service that holds customer passwords is the thing this design exists to avoid. Against a real IdP the controller is deleted outright.
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

- **Updates after commit.** The cache is written once `TransactionTemplate.execute` has returned, so the row is committed before the entry exists. Writing earlier would publish uncommitted data, and evicting earlier would let a concurrent read repopulate the entry from a pre-commit snapshot and pin it until the TTL.
- **Typed serialisation.** Explicit types avoid the deserialisation risks of generic JSON default typing.
- **Failure tolerance.** Cache errors do not fail requests. `CacheOutageTest` stops Redis and verifies that the service continues to operate.
- **Separate cache models.** The cache stores `AccountView` records rather than managed JPA entities.

## Security

The service is stateless, creates no sessions, and denies access by default. Token validation checks issuer, audience and subject as well as signature and expiry, so a token minted for another service, or one this service cannot read a customer id from, is refused at the edge.

Requests for another customer’s account return `404` to avoid disclosing its existence.

Signing keys are generated at startup rather than stored in the repository. This limits the demo to a single instance and invalidates tokens on restart. Production would use an HSM or KMS, `kid`-based rotation, and JWKS publication of both keys during rollover.

CSRF protection is disabled because authentication does not use cookies. This decision must be revisited if cookie-based refresh tokens are introduced.

## Resilience

Retry is applied once, at the customer lookup, and only to transient unavailability. Permanent failures are returned immediately: an unknown customer will still be unknown after a delay, so a retry spends time without changing the answer. The other integration points declare the interface and leave the policy to the real adapter — the same annotation copied onto a stand-in that cannot fail is tuning parameters, not a decision. Account-number allocation makes the point: its local implementation already redraws internally when the check digit rejects a seed, and the one failure it surfaces to a caller — an exhausted branch range — is permanent, so retrying it would be the mistake the paragraph above warns about.

Retries are deliberately not applied anywhere else.

- **The database.** Acquiring a connection times out after three seconds, so a request against a stopped database fails quickly instead of hanging. Retrying would multiply that wait while holding a request thread. The right response to a dependency that is down is to stop calling it, which needs a circuit breaker; that is listed as out of scope below.
- **A database that is reachable but blocked.** The three-second timeout covers acquiring a connection, not running a statement. A database that accepts connections but cannot answer — waiting on a lock, for instance — is not covered by it.
- **The account-slot contention loop.** When two requests compete for the same slot, the unique constraint rejects one and it tries again for the next slot. Nothing has failed and nothing is unavailable, so it retries at once; a backoff would only make it slower.

The allocator port takes a client reference. A remote allocator can succeed and have its response lost on the way back, and retrying without a reference would allocate a second number; the reference lets the far side recognise the repeat and return the first result. The local allocator ignores it, because it takes its number from a database sequence inside the caller's transaction — if that transaction fails, the number is simply never used and there is nothing to reconcile.

**Account opening is idempotent.** `POST /accounts` honours an `Idempotency-Key` header. The key is claimed with a single Redis `SET NX`, so two requests carrying the same key cannot both proceed; a key whose request has completed replays the original `201` rather than opening a second account. A key reused with a different request body returns `422` rather than the earlier result, because a retry repeats its request and anything else is a client defect.

This matters because of the account limit. A response lost in transit, followed by a retry, would otherwise consume one of the customer's five slots with no way for them to tell.

**An account resolved for the wrong customer is an incident, not a 404.** The two ownership checks in `AccountController` look alike and mean opposite things. On `GET /accounts/{id}` the caller chose the id, so a mismatch is expected — someone probing, or a stale link — and the answer is `404`, quietly, because `403` would confirm the account exists. On the opening path the id came from an idempotency entry namespaced by the customer, or from the write that had just created it, so a mismatch cannot happen unless that namespacing or that write is wrong. It is refused with a `500` that says nothing specific, and recorded on the audit logger at error with both customer ids and the account id — without the actual owner, the line reports that an invariant broke and gives nobody a way to find out how. `IdempotencyTest` forces the condition through the store and fails if the check is removed, because a defence nothing exercises is a comment.

**`IdempotencyStore.performOnce` is the only way in, and the primitives are closed.** Claim, perform, then complete or release is an order a caller can get wrong, and getting it wrong opens the second account this exists to prevent. Those three are package-private, so the protocol cannot be reassembled elsewhere, and the record it keeps never leaves the package. The endpoint supplies only what is irreducibly its own: how to do the work, and how to turn the stored id back into an answer. Both the first response and a replayed one are built by that same function, so they cannot drift apart; the cost is one read of a row the request just committed, which the write-through cache has already warmed.

A filter was considered and rejected. It would have to buffer and replay the response body, where this stores an account id and rebuilds the answer — a customer name sitting in Redis for the retention period is exactly what the log-hygiene work avoids elsewhere. It sees raw bytes, so the fingerprint would cover whitespace and a reformatted retry would read as a different request. And it would need ordering after authentication to scope keys by customer, adding a second ordering constraint to a filter chain that already has a delicate one.

**The idempotency store fails closed, and the cache does not.** A cache failure is swallowed because the correct answer is still available from PostgreSQL. The idempotency store is a correctness control: degrading it silently would reinstate the duplicate-account defect at the moment it is most likely to occur, because a caller retries when something is already wrong. The same Redis instance therefore has two failure policies. Moving the store to PostgreSQL and into the opening transaction is the alternative; making the control best-effort is not.

The header is optional so the API can be exercised without it. A production API would require it on unsafe methods, because the protection is worth what the least careful client does.

Readiness deliberately excludes dependencies so a shared dependency outage does not remove every service instance from routing. The service can continue returning `503` responses, while `/actuator/health` reports the dependency failure to operators.

## Observability

**Correlation IDs originate at the gateway.** nginx generates them using `$request_id`, covering requests that may never reach the service. Incoming values are validated and replaced when invalid to prevent log injection (CWE-117).

**Logs contain identifiers, not customer attributes.** `LogHygieneTest` checks DEBUG output from the application, Hibernate, and Spring. It detected entity-field logging by Hibernate’s `EntityPrinter`; the `org.hibernate` logging level is pinned to prevent a root-level change from re-enabling it. This verification also supports streaming logs to the operations console.

**Audit uses a separate named logger.** This supports separate destinations and retention periods—five years for AML/CFT and seven for tax—and keeps audit logging enabled when application logging is reduced. Refusal reasons are retained for compliance but withheld from API responses for communication through customer-facing staff.

## Feature flags

A cache kill switch allows operators to bypass a failing cache without deployment. It is evaluated on every call, takes effect on the next request, and cannot fail the request if evaluation fails.

A real flag service evaluates per user, so a flag can be on for some people and not others. That is not modelled here: the one flag is an operational kill switch, which is on for everyone or off for everyone, and an unused parameter threaded through every call site to suggest otherwise would be a claim the code does not support. Worth knowing for the real adapter: whatever identifies the user is sent to the flag service and appears in its dashboard, so it takes an opaque id and not a name or an email.

The one flag here is an operational kill switch, which stays. A release toggle would not: it is debt from the day it is added, and the thing that keeps a codebase free of them is a recorded owner and expiry, which is a flag service's job rather than an enum's.

**An unnamed account is named, but the nickname stays empty.** The brief makes the nickname optional and says nothing about what to show when it is absent, so the response carries a `displayName` alongside it: the nickname where there is one, otherwise `Savings account <n>` from the customer's own account sequence. That sequence is already unique per customer — the unique index guarantees it — so their five accounts are distinguishable rather than five rows reading the same thing.

Derived on the way out, never stored. `nickname` remains the customer's word and `displayName` remains ours, so a support screen or a statement run can still tell which is which. Computed on the server so every client names an account the same way, rather than each one inventing a rule.

## Front end

**Appearance follows the operating system, until someone says otherwise.** `useAppearance` reads `prefers-color-scheme` and keeps listening, because a desktop can switch it on a schedule while the page is open. An explicit choice overrides it and is kept per device. Every call to `matchMedia` is guarded: jsdom does not implement it, so a component that assumes it is there fails in tests rather than in a browser.

**A row is a union, not a bag of optional fields.** `AccountRow` is `PendingAccountRow | OpenedAccountRow`: a row this client invented, which has a nickname and nothing else, or one the server confirmed, which has every field the API returns. The earlier `Partial<Account>` said all of them might be missing at any time, which was never true and left the component guarding against cases that cannot happen. Narrowing on `pending` now gives the compiler the same knowledge a reader has.

**The form is hidden at the limit rather than disabled.** The badge beside the heading already reads "5 of 5 allowed" in red; a form that cannot be submitted is furniture. The server still refuses a sixth account regardless of what the screen shows - `AccountCapConcurrencyTest` is the proof - so nothing here is an enforcement.

**Radix Themes for the interface.** Accessible components and a token system, rather than a stylesheet of hand-written classes that has to be argued about. The application ships no CSS of its own at all. The account list stays a real `<ul>`, so a screen reader announces it as a list with a count rather than as unrelated boxes, and Radix's own `Reset` takes the bullets and padding off it. Components are grouped by feature - `accounts`, `auth`, and `ui` for what neither owns - so the folder says what the application does rather than what its files are.


**Access tokens are stored in memory.** This avoids persistent storage in `localStorage`, where injected scripts could retrieve them. The trade-off is that refreshing the page signs the user out. A production implementation would use a refresh token in an `httpOnly` cookie.

**Optimistic rows use the client reference as their React key.** Server-generated account IDs are unavailable when a row is first rendered. Reconciliation replaces the row with the server's version while carrying its key across: a pending row becoming an opened one is a move between members of a union, not one object gaining fields. Account creation does not invalidate the list, avoiding a refetch that would rebuild rows and reintroduce flicker.

**Retries reuse the idempotency key.** A retry after a timeout therefore represents the same account-opening request and cannot consume another account slot.

**A malformed `Idempotency-Key` is a validation failure, and is answered as one.** It is a constraint on the header parameter, so it produces the same `400` document as a rejected body field, naming `Idempotency-Key`. It previously threw the key-reused exception, which answered `422` telling a caller who had never used the key that it was already used — advice that cannot be followed, because a replacement generated the same way fails identically. Note that one constraint anywhere on a handler method routes that method's whole validation through `HandlerMethodValidationException` rather than `MethodArgumentNotValidException`; `ApiExceptionHandler` describes both parameter and body errors for that reason.

## Testing

Tests are selected for the failures they would detect rather than for coverage.

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

## Layout

**Three top-level packages, so the brief is visible from the tree.** `account` is what the assignment asks for. `integration` holds the systems a bank owns elsewhere — account number allocation, the customer master, feature flags, content moderation, log aggregation — each an interface with a stand-in behind it. `platform` holds the machinery every endpoint uses: security, caching, idempotency, observability, auditing, error handling.

**Each port sits with its adapter rather than in the domain.** `CustomerService` is named for the customer master, not for anything in the account model, and keeping it beside `DemoCustomerService` puts the seam and what is behind it in one place. The consequence is that `account` imports from `integration`, which a strict hexagonal reading would object to. The answer is that dependency inversion is satisfied by the domain depending on an interface whose implementation it cannot see; the directory a file sits in is not what inverts it. Enforcing the boundary rather than describing it would mean Spring Modulith or an ArchUnit rule, which is a larger claim than a service this size needs.

## Out of scope

| Item | Rationale or next step |
|---|---|
| Amendment and closure | An account is append-only in this scope, so there is no optimistic locking, no `updated_at`, and no update path. Adding one is where those belong. |
| Refresh-token rotation and reuse detection | Belongs to the production identity-provider integration. Existing `401` handling provides the integration point. |
| Circuit breaker | Connection-acquisition timeouts and fail-fast behaviour are implemented; a circuit breaker is the next resilience improvement. |
| Statement timeouts | Only connection acquisition is bounded. A `statement_timeout` on the database role, or a query timeout, is needed to bound a database that is reachable but blocked. |
| Automated base-image bumps | Bases are pinned by digest, which needs Renovate or Dependabot raising the bump and a scanner gating the merge. The pin without the bot is half the control; see Container images. |
| Rate limiting, ETags, CI, and load testing | Outside the assignment requirements. |
| Core banking adapter | Requires timeouts, a circuit breaker, and reconciliation for allocations whose responses are lost. |
| Durable audit events | Logger output can be lost when a container terminates. Production requires durable audit recording. |

## Container images

**Bases are pinned by digest, not by tag.** `eclipse-temurin:21-jre` is a floating tag: it moved from Ubuntu Noble to Ubuntu 26.04 with nothing in this repository changing. An image whose claim is that it builds from a clean clone cannot rest on a tag that means something different next month. The pinned digests are manifest lists, so `amd64` and `arm64` both still resolve and the one-command promise holds on either.

**A digest pin only works with a bot behind it.** Pinning stops security patches arriving silently, which is the point, and also stops them arriving at all — a pinned base is a stale base in six months. The complete control is Renovate or Dependabot raising the bump as a pull request, the image scanner gating the merge, and a human approving it. Pinning alone is half the control, and the worse half if nobody says so. Renovate configuration is not included here because CI is out of scope for this assignment; the pin is the part that belongs in the repository either way.

**Temurin rather than a smaller base, deliberately.** Measured against the alternatives, this is the largest option: Temurin on Ubuntu is 366 MB over 140 OS packages, against 208 MB / 73 for the Alpine variant and 202 MB / 40 for distroless. Two reasons it still wins here:

- *Alpine is musl.* Nothing in this service would notice — the PostgreSQL driver is pure Java and Lettuce falls back to NIO — but the APM agents commonly deployed in banks ship glibc-only native agents, and an image that cannot be instrumented is not cheaper.
- *Distroless has no shell,* so the Compose healthcheck cannot run, and restoring it means shipping a static probe binary from an added build stage: machinery in a Java repository to serve a demo.

The choice of base in a regulated environment is a patching question rather than a size question — who rebuilds the image when a CVE is published, and whether the registry scanner has a policy gate on its origin. Temurin has a named vendor, a quarterly critical-patch cadence, and TCK certification, which is what that question is asking for. A production deployment would more likely use the bank's own hardened base, typically Red Hat UBI where there is a RHEL estate.

**curl is not installed.** It ships in the base image, so the `apt-get install` that was here did nothing but add a layer and a network dependency to the build. It is wanted only for the Compose healthcheck; Kubernetes probes from outside the container and needs nothing inside it. A future digest bump that dropped curl would fail the healthcheck at the bump, which is a loud failure at a reviewed moment rather than a silent one.

**The container does not run as root,** and the JVM is told the cgroup memory limit (`MaxRAMPercentage`) and to die rather than limp on `OutOfMemoryError`.

## Production considerations

- **Account retention:** No delete endpoint is provided. Accounts should be closed and retained according to statutory requirements.
- **Account lifecycle:** Pending, active, dormant, frozen, and closed states are not modelled. New Zealand dormancy requirements would also need to be addressed.
- **Account-number allocation:** Production numbers must come from the core banking system using a range registered with Payments NZ.
- **Customer due diligence:** Account opening requires KYC checks, screening, and an approval record.
- **Operational controls:** Customer-visible changes, including feature-flag changes, require dual approval. The demo’s management endpoints are unauthenticated and must be secured before production use. This covers the feature-flag endpoint and the log tail; the log tail is the more sensitive of the two, because it streams application log lines.
