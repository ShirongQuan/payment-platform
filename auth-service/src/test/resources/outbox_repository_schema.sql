drop table if exists outbox_event;

create table outbox_event (
    event_id uuid primary key,
    version bigint,
    aggregate_type varchar(20) not null,
    aggregate_id uuid not null,
    event_type varchar(40) not null,
    payload varchar(2000) not null,
    status varchar(30) not null,
    retry_count int not null,
    last_error varchar(200),
    created_at timestamp with time zone not null,
    next_attempt_at timestamp with time zone not null,
    claimed_at timestamp with time zone,
    claim_until timestamp with time zone,
    published_at timestamp with time zone,
    idempotency_key varchar(40) not null,
    correlation_id uuid not null,
    trace_parent varchar(55)
);

