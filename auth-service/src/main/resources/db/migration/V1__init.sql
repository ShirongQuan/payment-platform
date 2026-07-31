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
    merchant_reference varchar(20),
    amount decimal(19,2) not null default 0,
    currency_code varchar(3) not null,
    authorisation_status varchar(10) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,

    constraint chk_currency_code_iso check (
        currency_code = upper(currency_code) and char_length(currency_code) = 3
    ),
    constraint chk_authorisation_amount_non_negative check (amount >= 0),
    constraint chk_authorisation_status check (authorisation_status in ('AUTHORISED', 'CAPTURED', 'REVERSED', 'DECLINED')),

    constraint fk_authorisation_account foreign key (account_id) references account(account_id)

);
    -- Speeds up reads by account and recent creation time
    create index if not exists idx_authorisation_account_created_at
        on authorisation (account_id, created_at desc);

create table authorisation_event (
    event_id uuid primary key,
    authorisation_id uuid not null,
    account_id uuid not null,
    event_type varchar(40) not null,
    idempotency_key varchar(20) not null,
    amount decimal(19,2) not null default 0,
    currency_code varchar(3) not null,
    reason_code varchar(30),
    correlation_id uuid,
    created_at timestamp with time zone not null,

    constraint fk_authorisation_event_authorisation
        foreign key (authorisation_id) references authorisation(authorisation_id),

    constraint fk_authorisation_event_account
        foreign key (account_id) references account(account_id),

    constraint chk_authorisation_event_type check (
        event_type in (
            'AUTHORISATION_AUTHORISED',
            'AUTHORISATION_CAPTURED',
            'AUTHORISATION_REVERSED',
            'AUTHORISATION_DECLINED'
        )
    ),

    constraint chk_authorisation_event_amount_non_negative check (
        amount >= 0
    ),

    constraint chk_authorisation_event_currency_code_iso check (
        currency_code is null or (
            currency_code = upper(currency_code) and char_length(currency_code) = 3
        )
    )
);

create unique index  if not exists uq_authorisation_event_account_eventtype_idempotency
    on authorisation_event (account_id, event_type, idempotency_key);

create unique index  if not exists  uq_account_id_idempotency_created_at on authorisation_event
 (account_id, idempotency_key, created_at);

create table outbox_events (
    event_id uuid primary key,
    version bigint not null default 0,
    aggregate_type varchar(20) not null,
    aggregate_id uuid not null,
    event_type varchar(30) not null,
    payload jsonb not null,
    status varchar(30) not null,
    retry_count int not null default 0,
    last_error varchar(100),
    created_at timestamp with time zone not null,
    published_at timestamp with time zone,
    next_attempt_at timestamp with time zone not null,
    idempotency_key varchar(30) not null,
    correlation_id uuid,  -- TODO not null,  generate the correlation_id in the app and save to the table
    claimed_at timestamp with time zone,
    claim_until timestamp with time zone,

    constraint chk_outbox_events_status check (status in ('PENDING', 'PUBLISHED', 'FAILED'))

);

    -- Speeds up outbox polling by status and creation time
    create index if not exists idx_outbox_event_claim on outbox_events (status, next_attempt_at, created_at);

    create index if not exists idx_outbox_events_claim_until on outbox_events (status, claim_until);