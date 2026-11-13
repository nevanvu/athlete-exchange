-- Whether a trader's name appears on leaderboards and the feed (otherwise "Anonymous trader").
alter table users add column portfolio_public boolean not null default false;

-- Notable moves: halts, big trades, IPO results, payouts. Already worded for display.
create table feed_items (
    id                uuid primary key,
    kind              varchar(30)  not null,
    slate_id          uuid,
    athlete_market_id uuid,
    headline          varchar(300) not null,
    detail            varchar(300),
    occurred_at       timestamptz  not null
);

create index idx_feed_items_recent on feed_items (occurred_at desc);

-- Market-abuse signals for review. One row per (kind, market, pair of users), updated as it grows.
create table surveillance_alerts (
    id                uuid primary key,
    kind              varchar(40)  not null,
    athlete_market_id uuid         not null references athlete_markets (id),
    user_a            uuid         not null references users (id),
    user_b            uuid         not null references users (id),
    occurrences       int          not null,
    detail            varchar(400) not null,
    first_seen_at     timestamptz  not null,
    last_seen_at      timestamptz  not null,
    status            varchar(20)  not null default 'OPEN',
    constraint uq_surveillance_alert unique (kind, athlete_market_id, user_a, user_b)
);

create index idx_surveillance_open on surveillance_alerts (status, last_seen_at desc);
