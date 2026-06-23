
-- create table

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