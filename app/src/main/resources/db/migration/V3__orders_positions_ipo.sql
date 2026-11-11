-- System user that owns house inventory (unsold IPO shares). Cannot log in.
insert into users (id, username, email, password_hash)
values ('00000000-0000-0000-0000-000000000010', 'house', 'house@system.local', '!');

alter table athlete_markets add column ipo_clearing_price_cents bigint;
alter table athlete_markets add column last_trade_price_cents bigint;

create table positions (
    id                 uuid primary key,
    user_id            uuid        not null references users (id),
    athlete_market_id  uuid        not null references athlete_markets (id),
    quantity           int         not null default 0,
    locked_quantity    int         not null default 0,
    total_cost_cents   bigint      not null default 0,
    realized_pnl_cents bigint      not null default 0,
    updated_at         timestamptz not null default now(),
    constraint uq_positions_user_market unique (user_id, athlete_market_id),
    constraint ck_positions_quantities check (quantity >= 0 and locked_quantity >= 0 and locked_quantity <= quantity)
);

create table orders (
    id                uuid primary key,
    user_id           uuid        not null references users (id),
    athlete_market_id uuid        not null references athlete_markets (id),
    side              varchar(4)  not null,
    type              varchar(8)  not null,
    price_cents       bigint,
    quantity          int         not null,
    filled_quantity   int         not null default 0,
    status            varchar(20) not null,
    escrow_cents      bigint      not null default 0,
    locked_shares     int         not null default 0,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);

create index idx_orders_market_status on orders (athlete_market_id, status);
create index idx_orders_user on orders (user_id, created_at desc);

create table trades (
    id                uuid primary key,
    athlete_market_id uuid        not null references athlete_markets (id),
    taker_order_id    uuid        not null references orders (id),
    maker_order_id    uuid        not null references orders (id),
    buyer_user_id     uuid        not null references users (id),
    seller_user_id    uuid        not null references users (id),
    price_cents       bigint      not null,
    quantity          int         not null,
    executed_at       timestamptz not null default now()
);

create index idx_trades_market on trades (athlete_market_id, executed_at desc);

create table ipo_bids (
    id                 uuid primary key,
    user_id            uuid        not null references users (id),
    athlete_market_id  uuid        not null references athlete_markets (id),
    max_price_cents    bigint      not null,
    quantity           int         not null,
    allocated_quantity int         not null default 0,
    clearing_price_cents bigint,
    status             varchar(20) not null,
    created_at         timestamptz not null default now()
);

create index idx_ipo_bids_market_status on ipo_bids (athlete_market_id, status);
