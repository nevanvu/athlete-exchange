create table teams (
    id   uuid primary key,
    code varchar(10) not null unique,
    name varchar(80) not null
);

create table athletes (
    id       uuid primary key,
    team_id  uuid        not null references teams (id),
    name     varchar(100) not null,
    position varchar(10) not null
);

create table slates (
    id            uuid primary key,
    name          varchar(120) not null,
    status        varchar(20)  not null,
    ipo_opens_at  timestamptz  not null,
    ipo_closes_at timestamptz  not null,
    created_at    timestamptz  not null default now()
);

create table games (
    id           uuid primary key,
    slate_id     uuid        not null references slates (id),
    home_team_id uuid        not null references teams (id),
    away_team_id uuid        not null references teams (id),
    status       varchar(20) not null,
    starts_at    timestamptz not null
);

create table athlete_markets (
    id                       uuid primary key,
    slate_id                 uuid          not null references slates (id),
    athlete_id               uuid          not null references athletes (id),
    game_id                  uuid          not null references games (id),
    status                   varchar(10)   not null,
    projected_fantasy_points numeric(8, 2) not null,
    projected_minutes        numeric(5, 1) not null,
    total_shares             int           not null,
    created_at               timestamptz   not null default now(),
    constraint uq_market_slate_athlete unique (slate_id, athlete_id)
);

create table slate_transitions (
    id          uuid primary key,
    slate_id    uuid        not null references slates (id),
    from_status varchar(20) not null,
    to_status   varchar(20) not null,
    trigger     varchar(60) not null,
    occurred_at timestamptz not null default now()
);

create index idx_games_slate on games (slate_id);
create index idx_markets_slate on athlete_markets (slate_id);
create index idx_transitions_slate on slate_transitions (slate_id);
create index idx_slates_status on slates (status);
