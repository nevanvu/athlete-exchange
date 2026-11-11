create table users (
    id            uuid primary key,
    username      varchar(50)  not null unique,
    email         varchar(255) not null unique,
    password_hash varchar(100) not null,
    created_at    timestamptz  not null default now()
);

create table accounts (
    id            uuid primary key,
    owner_user_id uuid references users (id),
    type          varchar(32) not null,
    balance_cents bigint      not null default 0,
    created_at    timestamptz not null default now(),
    constraint uq_accounts_owner_type unique (owner_user_id, type)
);

create table ledger_transactions (
    id              uuid primary key,
    idempotency_key varchar(120) not null unique,
    type            varchar(40)  not null,
    created_at      timestamptz  not null default now()
);

create table ledger_entries (
    id             uuid primary key,
    transaction_id uuid        not null references ledger_transactions (id),
    account_id     uuid        not null references accounts (id),
    amount_cents   bigint      not null,
    created_at     timestamptz not null default now(),
    constraint ck_entries_nonzero check (amount_cents <> 0)
);

create index idx_ledger_entries_account on ledger_entries (account_id);
create index idx_ledger_entries_transaction on ledger_entries (transaction_id);

-- System accounts (fixed ids referenced from code).
insert into accounts (id, owner_user_id, type, balance_cents)
values ('00000000-0000-0000-0000-000000000001', null, 'HOUSE', 0),
       ('00000000-0000-0000-0000-000000000002', null, 'SETTLEMENT_POOL', 0);
