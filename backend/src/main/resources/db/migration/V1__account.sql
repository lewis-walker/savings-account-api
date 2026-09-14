-- The single table.
--
-- Two things are enforced here rather than in Java, deliberately:
--
--   1. "A customer cannot create more than 5 accounts."
--      Application-side count-then-insert is vulnerable to race conditions.
--      The database is the only place
--      that can decide this correctly under concurrency.
--
--   2. Nickname length. Bean Validation rejects it at the edge with a good
--      error message; this is the backstop for anything reaching the table by
--      another path.

create table account (
    id              uuid         primary key,
    account_number  varchar(32)  not null unique,

    -- Who owns the account. Taken from the JWT 'sub' claim
    customer_id     uuid         not null,

    -- The name recorded on the account (mandatory input, per the brief).
    customer_name   varchar(140) not null,

    nickname        varchar(30),

    -- A slot in the customer's 1..5 series, not a running count. See note 1 above
    -- and the partial index below.
    slot_no         smallint     not null,

    -- varchar over a Postgres enum type: altering an enum is a migration with
    -- transaction restrictions, and this column is read by a plain string comparison.
    status          varchar(16)  not null default 'OPEN',

    -- No version or updated_at: nothing amends an account in this scope, so optimistic
    -- locking and a modification timestamp would be columns that never change.
    created_at      timestamptz  not null default now(),

    constraint account_slot_in_range
        check (slot_no between 1 and 5),
    constraint account_status_known
        check (status in ('OPEN', 'CLOSED')),
    constraint account_nickname_length
        check (nickname is null or char_length(nickname) between 5 and 30),
    constraint account_customer_name_present
        check (char_length(btrim(customer_name)) > 0)
);

-- The cap. Five slots, each occupiable by at most one OPEN account, so a customer
-- cannot hold six open accounts however many requests arrive at once.
--
-- Scoped to OPEN rather than covering the whole table, so closing an account returns
-- its slot to the pool. The rule survives a lifecycle this service does not yet have;
-- an unscoped unique index on a monotonic counter would not.
--
-- The predicate here is the definition of "occupies a slot". The finder in
-- AccountRepository repeats it exactly: a different spelling loses the index and,
-- worse, lets the two drift apart.
create unique index account_open_slot_uq
    on account (customer_id, slot_no)
    where status = 'OPEN';

create index account_customer_idx on account (customer_id);

-- Account numbers come from a sequence - they are not the primary key,
-- so the externally visible identifier and the internal one stay independent.
create sequence account_number_seq start with 1 increment by 1;
