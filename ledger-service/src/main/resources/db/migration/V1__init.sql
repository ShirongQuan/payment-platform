-- Initial ledger schema: read-optimized projections + immutable event log + deduplication table.

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
    created_at timestamp with time zone not null default now(),

    -- Stores original event payload to support audit/debug and future projection rebuilds.
    payload jsonb not null,

    constraint chk_ledger_entry_aggregate_type
        check (aggregate_type in ('ACCOUNT', 'AUTHORISATION')),

    constraint chk_ledger_entry_currency_code_iso check (
            currency_code = upper(currency_code) and char_length(currency_code) = 3
    ),

    constraint chk_ledger_entry_amount_non_negative check (
        amount is null or amount >= 0
    )
);

create index if not exists idx_ledger_entry_account_occurred_at
    on ledger_entry (account_id, occurred_at desc);

create index if not exists idx_ledger_entry_authorisation_occurred_at
    on ledger_entry (authorisation_id, occurred_at desc);

create index if not exists idx_ledger_entry_event_type_occurred_at
    on ledger_entry (event_type, occurred_at desc);

create index if not exists idx_ledger_entry_aggregate_occurred_at
    on ledger_entry (aggregate_type, aggregate_id, occurred_at desc);


create table ledger_event_log (
    event_id uuid primary key,
    aggregate_type varchar(20) not null,
    aggregate_id uuid not null,
    event_type varchar(30) not null,
    payload jsonb not null,
    correlation_id uuid,
    occurred_at timestamp with time zone not null,
    received_at timestamp with time zone not null default now()
);

create index if not exists idx_ledger_event_log_aggregate_occurred_at
    on ledger_event_log (aggregate_type, aggregate_id, occurred_at desc);

create index if not exists idx_ledger_event_log_event_type_occurred_at
    on ledger_event_log (event_type, occurred_at desc);

-- Event-consumer deduplication guard: first insert wins, duplicates are ignored.
create table processed_event (
    event_id uuid primary key,
    event_type varchar(30) not null,
    processed_at timestamp with time zone not null default now()
);


