-- The brief asks for a single table, and this is it.
--
-- Two things are enforced here rather than in Java, deliberately:
--
--   1. "A customer cannot create more than 5 accounts."
--      Application-side count-then-insert is a race: two concurrent requests
--      both read 4, both insert, customer ends up with 6. Instead each account
--      carries a per-customer sequence_no, unique per customer. Two concurrent
--      inserts compute the same next value and one loses on the unique index;
--      the CHECK then caps the series at 5. The database is the only place
--      that can decide this correctly under concurrency.
--
--   2. Nickname length. Bean Validation rejects it at the edge with a good
--      error message; this is the backstop for anything reaching the table by
--      another path. The edge check is for humans, this one is for the invariant.

create table account (
    id              uuid         primary key,
    account_number  varchar(32)  not null unique,

    -- Who owns the account. Taken from the JWT 'sub' claim, never from the
    -- request body: a caller must not be able to create accounts for someone else.
    customer_id     uuid         not null,

    -- The name recorded on the account (mandatory input, per the brief).
    customer_name   varchar(140) not null,

    nickname        varchar(30),

    -- 1..5, dense per customer. See note 1 above.
    sequence_no     smallint     not null,

    -- Optimistic locking. Also lets the read cache stay monotonic: a late write
    -- can never overwrite a newer entry with an older one.
    version         bigint       not null default 0,

    created_at      timestamptz  not null default now(),
    updated_at      timestamptz  not null default now(),

    constraint account_within_cap
        check (sequence_no between 1 and 5),
    constraint account_nickname_length
        check (nickname is null or char_length(nickname) between 5 and 30),
    constraint account_customer_name_present
        check (char_length(btrim(customer_name)) > 0)
);

-- The race-loser. Named, because the service distinguishes this violation
-- (retry, someone beat us to the number) from account_within_cap (refuse, the
-- customer is full). Catching DataIntegrityViolationException without knowing
-- which constraint fired is how you end up retrying a permanent failure.
create unique index account_customer_sequence_uq
    on account (customer_id, sequence_no);

create index account_customer_idx on account (customer_id);

-- Account numbers come from a sequence rather than a random draw: no collision
-- retry loop, no birthday-problem reasoning, and they are not the primary key,
-- so the externally visible identifier and the internal one stay independent.
create sequence account_number_seq start with 1 increment by 1;
