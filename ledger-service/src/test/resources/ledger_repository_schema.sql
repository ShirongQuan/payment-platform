drop table if exists ledger_entries;
drop table if exists processed_events;

create table ledger_entries (
    entry_id uuid primary key,
    event_id uuid not null unique,
    aggregate_type varchar(20) not null,
    aggregate_id uuid not null,
    account_id uuid,
    authorisation_id uuid,
    event_type varchar(30) not null,
    entry_status varchar(20),
    amount decimal(19,2),
    currency_code varchar(3) not null,
    merchant_reference varchar(20),
    idempotency_key varchar(30),
    occurred_at timestamp with time zone not null,
    created_at timestamp with time zone not null,
    payload varchar(2000) not null
);

create index if not exists idx_ledger_entries_account_occurred_at
    on ledger_entries (account_id, occurred_at desc);

create index if not exists idx_ledger_entries_aggregate_occurred_at
    on ledger_entries (aggregate_type, aggregate_id, occurred_at desc);

create table processed_events (
    event_id uuid primary key,
    event_type varchar(30) not null,
    processed_at timestamp with time zone not null
);

