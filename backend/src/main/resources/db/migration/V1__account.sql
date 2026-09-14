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

    -- 1..5 See note 1 above.
    sequence_no     smallint     not null,

    -- No version or updated_at: nothing amends an account in this scope, so optimistic
    -- locking and a modification timestamp would be columns that never change.
    created_at      timestamptz  not null default now(),

    constraint account_within_cap
        check (sequence_no between 1 and 5),
    constraint account_nickname_length
        check (nickname is null or char_length(nickname) between 5 and 30),
    constraint account_customer_name_present
        check (char_length(btrim(customer_name)) > 0)
);

-- Named unique key to identify race-condition collisions.
create unique index account_customer_sequence_uq
    on account (customer_id, sequence_no);

create index account_customer_idx on account (customer_id);

-- Account numbers come from a sequence - they are not the primary key,
-- so the externally visible identifier and the internal one stay independent.
create sequence account_number_seq start with 1 increment by 1;
