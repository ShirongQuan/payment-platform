-- create tables

create table account (
    account_id uuid primary key,
    version bigint not null default 0,
    currency_code varchar(3) not null,
    account_status varchar(10) not null,
    available_balance decimal(19,2) not null default 0,
    reserved_balance decimal(19,2) not null default 0,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,

    constraint chk_currency_code_iso check (
        currency_code = upper(currency_code) and char_length(currency_code) = 3
    ),
    constraint chk_available_balance_non_negative check (available_balance >=0),
    constraint chk_reserved_balance_non_negative check (reserved_balance >=0),
    constraint chk_account_status check (account_status in ('ACTIVE', 'LOCKED', 'INACTIVE'))
);

create table authorisation (
    authorisation_id uuid primary key,
    version bigint not null default 0,
    account_id uuid not null,
    idempotency_key varchar(20) not null,
    merchant_reference varchar(20),
    amount decimal(19,2) not null default 0,
    currency_code varchar(3) not null,
    authorisation_status varchar(10) not null,
    failure_reason varchar(30) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,

    constraint chk_currency_code_iso check (
        currency_code = upper(currency_code) and char_length(currency_code) = 3
    ),
    constraint chk_authorisation_amount_non_negative check (amount >= 0),
    constraint chk_authorisation_status check (authorisation_status in ('AUTHORISED', 'CAPTURED', 'REVERSED', 'DECLINED')),

    constraint fk_authorisation_account foreign key (account_id) references account(account_id),

    -- ensure idempotency
    constraint uq_account_id_idempotency_key unique (account_id, idempotency_key)

);
    -- Speeds up reads by account and recent creation time
    create index if not exists idx_authorisation_account_created_at
        on authorisation (account_id, created_at desc);

create table outbox_events (
    event_id uuid primary key,
    aggregate_type varchar(20) not null,
    aggregate_id uuid not null,
    event_type varchar(30) not null,
    payload jsonb not null,
    status varchar(30) not null,
    retry_count int default 0,
    last_error varchar(30),
    created_at timestamp with time zone not null,
    published_at timestamp with time zone,
    idempotency_key varchar(30) not null,
    correlation_id uuid,  -- TODO not null,  generate the correlation_id in the app and save to the table

    constraint chk_outbox_events_status check (status in ('PENDING', 'PUBLISHED', 'FAILED'))

);


    -- Speeds up outbox polling by status and creation time
    create index if not exists idx_outbox_events_status_created_at on outbox_events (status, created_at);
