# Design decisions

Notes on the main choices and limitations in this assessment.

## Scope and assumptions

- **One accounts table.** Customer lookup, account-number allocation, content moderation, and feature flags use interfaces with demo implementations.
- **Customer identity comes from the token.** This identifies whose five-account limit applies and restricts account access to its owner.
- **Customer name comes from the demo Customers API.** This departs from the brief’s request-body input requirement: the submission assumes an existing customer record and stores a snapshot of its name on the account.
- **Accounts can be created and retrieved only.** Updates, closure, and deletion are outside scope.

## Validation and identifiers

Nicknames are optional. Responses include a derived `displayName`: the nickname when supplied, otherwise `Savings account <n>`, using the account’s slot number. The fallback is not stored as a nickname.

Account IDs use UUIDv4 to avoid enumerable URL identifiers. Account numbers are sequential and use the New Zealand format, `BB-bbbb-AAAAAAA-SSS`, with a modulus-11 check. Bank code 99 is deliberately unregistered; these are demonstration numbers.

Malformed `Idempotency-Key` headers return `400` with the same error format as invalid request-body fields.

## Five-account limit

The database enforces the limit. Each account holds a `slot_no` of 1–5, and a unique index on `(customer_id, slot_no)` covering only rows with status `OPEN` makes each slot exclusive: five slots holding at most one open account each cap the customer at five. A count followed by an insert alone would allow concurrent requests to exceed the limit.

Scoping the index to open accounts keeps the rule correct if accounts can later be closed. An unscoped index over an always-increasing counter also caps at five, but only while nothing leaves the set; closing the fifth account would take the next counter value to six and refuse a customer holding four. Closure itself is out of scope. The status column is here because it is what the constraint counts, not as the start of a lifecycle.

`AccountRepository.nextFreeSlot` proposes the lowest free slot and is the only thing that reports a full customer. It reads committed rows, so the slot it offers may already be taken by a request that has not committed; the unique index settles that and the caller retries in a new transaction. Each attempt uses `TransactionTemplate` to make that boundary explicit; retries must run outside the transaction aborted by the constraint violation. The finder repeats the index predicate exactly, keeping one definition of an occupied slot, and answers “full” before an account number is allocated.

`AccountCapConcurrencyTest` releases sixteen requests simultaneously and checks that exactly five succeed. `AccountSlotReuseTest` runs the update a close endpoint would issue, then checks that the freed slot is reused, that the limit still holds, and that the database refuses a duplicate open slot to a writer bypassing the service.

## Idempotency

`POST /accounts` accepts an optional `Idempotency-Key`. Redis atomically claims the key, scoped to the customer. Completed requests replay the original `201`; reuse with a different request body returns `422`. The front end preserves the key when retrying after a timeout.

`IdempotencyStore.performOnce` encapsulates claiming, executing, and completing or releasing a request. It stores the account ID and reconstructs the response, rather than retaining the response body.

Redis has two distinct failure policies:

- **Cache operations fail open:** requests can fall back to PostgreSQL.
- **Idempotency operations fail closed:** requests using a key are refused if duplicate-request protection is unavailable.

Requests without a key have no idempotency protection.

## Caching and dependency failures

Redis caches account reads as the optional caching exercise. It also caches customer lookups, which represent a remote call for infrequently changing data.

Cache entries use typed `AccountView` records rather than JPA entities and are written only after the database transaction commits. A runtime kill switch bypasses caching and is evaluated on every call.

Only transient customer-lookup failures receive delayed retries. Permanent failures and database connection failures are returned immediately. Account-slot contention retries separately, without backoff.

Database connection acquisition times out after three seconds. **Statement execution is not bounded**, so a reachable database blocked on a lock can still delay a request.

Readiness excludes dependencies, allowing the service to return `503` during an outage. The general health endpoint still reports dependency failures.

## Authentication and logging

The service is a stateless resource server. It validates token signature, expiry, issuer, audience, and a subject that can be read as a customer ID. Requests for another customer’s account return `404`.

`TokenController` and the shared password documented in the README support demo sign-in only. Signing keys are generated at startup, so tokens expire on restart and the demo supports one instance. Browser tokens are held in memory; refreshing the page signs the user out. CSRF protection is disabled because authentication does not use cookies.

nginx generates correlation IDs. The service validates incoming values before logging them. Logs contain identifiers rather than customer attributes, with a separate audit logger for account-opening outcomes.

An ownership mismatch during account creation or idempotency replay returns a generic `500` and records an audit error: unlike a caller requesting another account ID, this indicates an internal consistency failure.

## Front end

The front end demonstrates account opening with optimistic updates:

- Pending and confirmed rows use separate TypeScript types.
- Client references remain stable React keys when server responses arrive.
- Creation reconciles the existing row without refetching the list.
- The form is hidden at five accounts; enforcement remains in the API.

Radix Themes supplies the UI components. Appearance follows the operating system unless the user selects an override.

## Tests and running the demo

Backend integration tests use PostgreSQL and Redis through Testcontainers. The main checks cover concurrent account limits, idempotency, token validation, retry execution, log hygiene, cache outages, and the cache kill switch. Account-number validation uses a published valid number. The optimistic-update test checks that reconciliation preserves the DOM node.

Database unavailability was also checked manually by stopping PostgreSQL.

Backend tests run separately from the image build because Testcontainers requires Docker. Front-end tests run during their image build.

Container bases are pinned by digest for reproducible builds on `amd64` and `arm64`. The Java image uses Temurin, runs as a non-root user, and uses the base image’s `curl` for the Compose healthcheck.

## Limitations

This is a runnable assessment demo. It does not include real banking integrations, refresh tokens, circuit breakers, statement timeouts, durable audit storage, rate limiting, CI, load testing, or automated image updates.

The feature-flag and log-tail management endpoints are unauthenticated for the demo. Audit records are log output and are not guaranteed durable.
