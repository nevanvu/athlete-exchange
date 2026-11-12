alter table games add column period int not null default 0;
alter table games add column clock_seconds_remaining int not null default 0;
alter table games add column status_sequence bigint not null default 0;

-- Every ingested event, raw. Doubles as the idempotency registry (event_id is the key) and the replay log.
create table game_events (
    event_id    varchar(26) primary key,
    game_id     uuid        not null,
    slate_id    uuid,
    type        varchar(30) not null,
    sequence    bigint      not null,
    source      varchar(40) not null,
    occurred_at timestamptz not null,
    ingested_at timestamptz not null default now(),
    corrects    varchar(26),
    payload     text        not null
);

create index idx_game_events_game_seq on game_events (game_id, sequence);

-- Latest cumulative stat line per athlete per game (replaced wholesale by newer sequences).
create table athlete_game_stats (
    game_id        uuid          not null references games (id),
    athlete_id     uuid          not null references athletes (id),
    stats          text          not null,
    minutes_played numeric(5, 1) not null default 0,
    fantasy_points numeric(8, 2) not null default 0,
    is_final       boolean       not null default false,
    as_of_sequence bigint        not null,
    as_of_event_id varchar(26)   not null,
    updated_at     timestamptz   not null default now(),
    primary key (game_id, athlete_id)
);

create table athlete_availability (
    game_id        uuid        not null references games (id),
    athlete_id     uuid        not null references athletes (id),
    status         varchar(20) not null,
    detail         varchar(200),
    as_of_sequence bigint      not null,
    as_of_event_id varchar(26) not null,
    updated_at     timestamptz not null default now(),
    primary key (game_id, athlete_id)
);

create table news_events (
    event_id    varchar(26) primary key,
    game_id     uuid          not null,
    athlete_id  uuid          not null references athletes (id),
    headline    varchar(300)  not null,
    impact      numeric(5, 3) not null,
    confidence  numeric(4, 3) not null,
    occurred_at timestamptz   not null
);

create index idx_news_events_athlete on news_events (athlete_id, occurred_at desc);

-- Transactional outbox for everything the app publishes to Kafka.
create table outbox (
    id           uuid primary key,
    topic        varchar(80)  not null,
    message_key  varchar(120) not null,
    payload      text         not null,
    created_at   timestamptz  not null default now(),
    published_at timestamptz
);

create index idx_outbox_unpublished on outbox (created_at) where published_at is null;
