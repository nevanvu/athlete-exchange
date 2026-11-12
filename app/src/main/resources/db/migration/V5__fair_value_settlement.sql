alter table athlete_markets add column fair_value_cents bigint;
alter table athlete_markets add column fair_value_as_of_event_id varchar(26);
alter table athlete_markets add column fair_value_updated_at timestamptz;
alter table athlete_markets add column settlement_value_cents bigint;
alter table athlete_markets add column settlement_fantasy_points numeric(8, 2);

-- One row per change of a market's fair value, with the model inputs that produced it.
create table fair_value_ticks (
    id                      bigserial primary key,
    athlete_market_id       uuid          not null references athlete_markets (id),
    fair_value_cents        bigint        not null,
    fp_scored               numeric(8, 2) not null,
    fp_remaining            numeric(8, 2) not null,
    availability            numeric(4, 2) not null,
    news_factor             numeric(6, 4) not null,
    game_fraction_remaining numeric(6, 4) not null,
    as_of_event_id          varchar(26),
    computed_at             timestamptz   not null
);

create index idx_fair_value_ticks_market on fair_value_ticks (athlete_market_id, id);

create table settlement_runs (
    id                    uuid primary key,
    slate_id              uuid        not null unique references slates (id),
    settled_at            timestamptz not null,
    total_paid_cents      bigint      not null,
    ledger_transaction_id uuid
);

create table settlement_payouts (
    id                uuid primary key,
    settlement_run_id uuid   not null references settlement_runs (id),
    athlete_market_id uuid   not null references athlete_markets (id),
    user_id           uuid   not null references users (id),
    quantity          int    not null,
    value_cents       bigint not null,
    amount_cents      bigint not null
);

create index idx_settlement_payouts_market on settlement_payouts (athlete_market_id);

create table settlement_corrections (
    id                     uuid primary key,
    athlete_market_id      uuid          not null references athlete_markets (id),
    event_id               varchar(26)   not null unique,
    old_value_cents        bigint        not null,
    new_value_cents        bigint        not null,
    new_fantasy_points     numeric(8, 2) not null,
    total_adjustment_cents bigint        not null,
    applied                boolean       not null,
    reason                 varchar(120),
    created_at             timestamptz   not null default now()
);
