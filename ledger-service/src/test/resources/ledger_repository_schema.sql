drop table if exists ledger_entry;
drop table if exists processed_event;

create table ledger_entry (
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

create index if not exists idx_ledger_entry_account_occurred_at
    on ledger_entry (account_id, occurred_at desc);

create index if not exists idx_ledger_entry_aggregate_occurred_at
    on ledger_entry (aggregate_type, aggregate_id, occurred_at desc);

create table processed_event (
    event_id uuid primary key,
    event_type varchar(30) not null,
    processed_at timestamp with time zone not null
);

