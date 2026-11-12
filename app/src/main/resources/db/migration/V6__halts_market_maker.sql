-- Trading halts: current halt on the market row, full history in market_halts.
alter table athlete_markets add column halt_reason varchar(120);
alter table athlete_markets add column halted_at timestamptz;
alter table athlete_markets add column resume_after timestamptz;

create table market_halts (
    id                      uuid primary key,
    athlete_market_id       uuid         not null references athlete_markets (id),
    reason                  varchar(120) not null,
    trigger_event_id        varchar(26),
    fair_value_before_cents bigint,
    fair_value_after_cents  bigint,
    halted_at               timestamptz  not null,
    resume_after            timestamptz,
    resumed_at              timestamptz
);

create index idx_market_halts_market on market_halts (athlete_market_id, halted_at);
create index idx_markets_halted on athlete_markets (resume_after) where status = 'HALTED';

-- The outbox relays in insertion order; a sequence breaks ties that timestamps cannot
-- (a halt and the repricing that caused it are written in the same transaction).
alter table outbox add column seq bigserial;
drop index idx_outbox_unpublished;
create index idx_outbox_pending on outbox (seq) where published_at is null;

-- The house system user becomes the market maker: it already owns unsold IPO inventory and now
-- gets cash and escrow accounts, seeded with $250,000 from the house account by a ledger transaction.
insert into accounts (id, owner_user_id, type, balance_cents)
values ('00000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000010', 'USER_CASH', 25000000),
       ('00000000-0000-0000-0000-000000000012', '00000000-0000-0000-0000-000000000010', 'USER_ESCROW', 0);

update accounts set balance_cents = balance_cents - 25000000 where id = '00000000-0000-0000-0000-000000000001';

insert into ledger_transactions (id, idempotency_key, type)
values ('00000000-0000-0000-0000-000000000013', 'seed:market-maker', 'SEED_GRANT');

insert into ledger_entries (id, transaction_id, account_id, amount_cents)
values ('00000000-0000-0000-0000-000000000014', '00000000-0000-0000-0000-000000000013',
        '00000000-0000-0000-0000-000000000001', -25000000),
       ('00000000-0000-0000-0000-000000000015', '00000000-0000-0000-0000-000000000013',
        '00000000-0000-0000-0000-000000000011', 25000000);
