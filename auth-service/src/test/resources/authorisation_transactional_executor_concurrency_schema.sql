drop table if exists outbox_event;
drop table if exists authorisation_event;
drop table if exists authorisation;
drop table if exists account;

create table account (
    account_id uuid primary key,
    version bigint not null default 0,
    account_status varchar(10) not null,
    currency_code varchar(3) not null,
    available_balance decimal(19,2) not null,
    reserved_balance decimal(19,2) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create table authorisation (
    authorisation_id uuid primary key,
    version bigint not null default 0,
    account_id uuid not null,
    amount decimal(19,2) not null,
    currency_code varchar(3) not null,
    merchant_reference varchar(20),
    authorisation_status varchar(20) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint fk_authorisation_account foreign key (account_id) references account(account_id)
);

create table authorisation_event (
    event_id uuid primary key,
    authorisation_id uuid not null,
    account_id uuid not null,
    event_type varchar(40) not null,
    idempotency_key varchar(20) not null,
    amount decimal(19,2) not null,
    currency_code varchar(3) not null,
    reason_code varchar(30),
    correlation_id uuid,
    created_at timestamp with time zone not null,
    constraint fk_authorisation_event_authorisation
        foreign key (authorisation_id) references authorisation(authorisation_id),
    constraint fk_authorisation_event_account
        foreign key (account_id) references account(account_id)
);

create unique index if not exists uq_authorisation_event_account_eventtype_idempotency
    on authorisation_event (account_id, event_type, idempotency_key);

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
    correlation_id uuid
);

