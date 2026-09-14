# Decisions

The brief invites assumptions where requirements are thin, and TODOs where something
would be impractical to build. This is both, kept short.

## Assumptions

**Customer identity comes from the token, never the request body.** Accepting a customer
id would let any caller open accounts in anyone's name.

**The customer's name comes from a Customers API, not the body and not the token.**
Under the AML/CFT Act an account is opened for a customer whose identity is already
verified, so the name is standing data on that record. It is stored on the account as a
*snapshot* — the name the account was opened under is the audit fact; the customer master
stays authoritative for the current value, and drift between them is expected. It is not
in the token because tokens ride in a header on every request and headers get logged.

**Authentication is not this service's job.** `SecurityConfig` is a resource server;
`TokenController` is the OAuth 2.0 password grant, which OAuth 2.1 removes, and exists
only so the demo runs without an identity provider. It would be the first thing deleted.

**One table means one table.** No customers table, no users table. That is also the right
answer: a bank account API does not own customer records or credentials.

## The five-account cap is a concurrency problem

`count()` then `insert` passes every sequential test and still gives a customer six
accounts in production. Each account carries a per-customer `sequence_no`, unique per
customer and `CHECK`ed to 1..5, so two concurrent requests compute the same next value
and the index picks a winner. `AccountCapConcurrencyTest` fires sixteen simultaneous
opens and asserts exactly five land.

Three details in the code rather than here: Postgres aborts the transaction when a
constraint fires, so the retry must sit outside it; crossing `REQUIRES_NEW` needs a real
proxy, so the single attempt is a separate bean; and *which* constraint fired decides
whether to retry or refuse.

## Identifiers

**Two identifiers with deliberately opposite properties.** The `id` is a UUID because it
appears in URLs, and an enumerable id invites probing even behind an authorisation check.
The account number is sequential because it is semi-public by design — you hand it to
people so they can pay you — and is never a credential.

**Account numbers are New Zealand format** (`BB-bbbb-AAAAAAA-SSS`) satisfying the standard
modulus-11 check, with weights verified against a published valid number. Bank 99 is
deliberately not a registered bank.

**What a sequential number leaks is commercial, not personal**: two numbers bracket how
many accounts were opened between them. The real fix is format-preserving encryption —
keep the sequence so uniqueness is free, encrypt it into the same digit space so nothing
is inferable. Not built; named because knowing the name is most of it.

**UUIDv4, not v7.** v7 buys index locality this table will never need and costs a
creation timestamp embedded in an identifier that appears in URLs.

## Caching

**One cache earns its keep and one does not, and the difference is the point.** The
customer lookup is remote, on every opening, for data that changes rarely — that is what
a cache is for. Caching `GET /accounts/{id}` earns nothing: a primary-key lookup that
Postgres answers from its buffer pool in microseconds, to which Redis adds a network hop.
It is built because the brief asks.

Built correctly anyway, because what "correctly" costs is the interesting part:

- **Write-through after commit.** `@CachePut` before commit can publish a row that never
  exists; `@CacheEvict` before commit lets a concurrent read repopulate from the
  pre-commit snapshot. `AFTER_COMMIT` has neither problem.
- **Typed serializers.** Generic JSON with default typing writes a class name into every
  entry and instantiates whatever it reads back — a deserialization gadget for anyone with
  write access to the cache.
- **Failures are swallowed.** Spring's default error handler *rethrows*, so Redis going
  down turns an optimisation into a mandatory dependency. `CacheOutageTest` stops the
  container and asserts the service keeps working.

**The entity never reaches the cache.** `AccountView` is a record; `Account` is a managed
JPA object whose shape is the schema.

## Security

Deny by default, stateless, no sessions. The decoder validates issuer and audience, not
just signature and expiry — without an audience check this API accepts any valid token
minted for any other service in the estate.

**Someone else's account is `404`, not `403`.** A 403 confirms it exists, which is enough
to walk the estate.

**Signing keys are generated at startup.** A demo private key in a repository is still a
private key in a repository. The cost is written down: tokens do not survive a restart,
and it is single-instance. Production uses an HSM or KMS with `kid`-based rotation and
JWKS publishing both keys through a rollover.

**CSRF is off** because no cookie carries authority here. That changes the moment a
refresh token arrives in one.

## Resilience

**Retry is applied to the two integration ports and nowhere else**, and only to their
*unavailable* failures — retrying a permanent one spends the budget and delays a final
answer. Not the database: it already fails in three seconds, and three retries make the
caller wait nine while holding a thread. A dependency that is down needs a circuit
breaker, not more calls. Not the contention loop either: that is a race someone else won,
and the next slot is free now.

Retrying account allocation is only safe because of the client reference — a timed-out
allocation may have succeeded, and a blind retry opens a second account.

**Readiness excludes dependencies, deliberately.** If it did not, one shared database
blip would fail readiness on every instance at once and callers would get connection
failures instead of the `503` this service can return — a partial outage converted into a
total one. `/actuator/health` still reports DOWN so operators can see it.

## Observability

**The correlation id is minted by the gateway** (nginx, from `$request_id`), not the
browser. The edge sees requests this service never will. The header is still validated
and replaced rather than trusted: it is attacker-controlled input heading into a log file
(CWE-117), and where logs are evidence a forged entry is worse than a missing one.

**Logs carry identifiers, never attributes.** `LogHygieneTest` asserts it at DEBUG across
Hibernate and Spring, not just our own logging — it caught Hibernate's `EntityPrinter`
writing entity fields, which no amount of care in our own `toString` would have fixed.
`org.hibernate` is pinned so raising the root level during an incident cannot re-enable
it. That test is also why streaming logs to the ops console is acceptable at all.

**Audit is a separate named logger.** Different audience, different retention (five years
for AML/CFT, seven for tax), different sink. It also means turning application logging
down mid-incident cannot switch off the audit trail. Refusal reasons are recorded even
though the API withholds them from the caller — compliance needs to know; a customer
should hear it from a person.

## Feature flags

A cache kill switch, because that is what a bank uses flags for: taking a misbehaving
dependency out of the path in seconds without a deployment. Evaluated on every call, so
it takes effect on the next request; cannot throw, because a switch should not be able to
fail a request; evaluated against a context, so targeting is possible later; and that
context carries no personal data, because all of it goes to a third party's dashboard.

Flags are enumerated with their purpose and expected lifetime — each one is a branch in
production somebody has to delete. Kill switches are the exception that stays.

## Front end

**The access token lives in memory.** `localStorage` is readable by any script on the
page, so one XSS anywhere in the dependency tree hands over a working bearer token. The
cost is accepted: a refresh signs you out. Production answer is a refresh token in an
`httpOnly` cookie.

**The React key is a client ref, never the account id.** The API generates ids, so an
optimistic row has none yet; keying on one would remount the row when it arrives.
Rendering identity and domain identity are different things. Reconciliation merges the
server's values onto the existing row and preserves the key, and the create deliberately
does not invalidate the list — a refetch would rebuild rows and reintroduce the flicker.

**Retries reuse the idempotency key.** Otherwise "try again" after a timeout is
indistinguishable from "open another account", and against a cap of five a network blip
silently costs a slot.

## Not built

| | Why |
|---|---|
| Refresh tokens with rotation and reuse detection | Proving we know about it is the point, not building an OIDC provider. The 401 handling is the seam |
| Circuit breaker | The timeouts and fail-fast behaviour are here; the breaker is the next increment |
| Rate limiting, ETags, CI, load testing | Not asked for, and each would be a paragraph of value |
| The core banking adapter | Needs a timeout, a breaker, and a reconciliation job for allocations whose response was lost |
| Audit as durable domain events | A log line can be lost when a container is killed, and "probably recorded" is not a position to take in front of a regulator |

## Where this differs from a real bank

- **Accounts are never deleted.** There is no delete endpoint and there should not be.
  Accounts close; they do not disappear. Retention is statutory.
- **Accounts have a lifecycle, not a boolean.** Pending, active, dormant, frozen, closed —
  dormancy in New Zealand has a legal path. Not modelled here.
- **Account numbers come from the core**, from a range registered with Payments NZ. The
  registered prefix in the format is itself the evidence that an edge service cannot
  invent one.
- **Opening an account is a KYC event**, not a row insert: customer due diligence, screening,
  and a record of who approved what.
- **Four eyes** on anything customer-visible, including flag changes. The flags endpoint
  here is unauthenticated so the demo runs with one command, and that is the first thing
  that would change.
