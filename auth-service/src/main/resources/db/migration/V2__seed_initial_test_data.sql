-- Seed sample data for local development/testing.
-- Flyway executes this once, but inserts are also conflict-safe.

-- Align outbox status constraint with application statuses.
alter table outbox_events drop constraint if exists chk_outbox_events_status;
alter table outbox_events
    add constraint chk_outbox_events_status
    check (status in ('NEW', 'PUBLISHING', 'PUBLISHED', 'FAILED'));

insert into account (
    account_id,
    version,
    currency_code,
    account_status,
    available_balance,
    reserved_balance,
    created_at,
    updated_at
)
values
    ('11111111-1111-1111-1111-111111111111', 0, 'GBP', 'ACTIVE', 1000.00, 0.00, '2026-07-01T09:00:00Z', '2026-07-01T09:00:00Z'),
    ('22222222-2222-2222-2222-222222222222', 0, 'USD', 'ACTIVE', 500.00, 50.00, '2026-07-01T10:00:00Z', '2026-07-01T10:00:00Z'),
    ('33333333-3333-3333-3333-333333333333', 0, 'EUR', 'ACTIVE', 850.00, 20.00, '2026-07-01T13:00:00Z', '2026-07-01T13:00:00Z')
on conflict (account_id) do nothing;

insert into authorisation (
    authorisation_id,
    version,
    account_id,
    idempotency_key,
    merchant_reference,
    amount,
    currency_code,
    authorisation_status,
    failure_reason,
    created_at,
    updated_at
)
values
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 0, '11111111-1111-1111-1111-111111111111', 'idem-seed-001', 'merchant-seed-1', 25.00, 'GBP', 'AUTHORISED', '', '2026-07-01T11:00:00Z', '2026-07-01T11:00:00Z'),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 0, '22222222-2222-2222-2222-222222222222', 'idem-seed-002', 'merchant-seed-2', 80.00, 'USD', 'DECLINED', 'Insufficient funds', '2026-07-01T12:00:00Z', '2026-07-01T12:00:00Z'),
    ('cccccccc-cccc-cccc-cccc-cccccccccccc', 0, '33333333-3333-3333-3333-333333333333', 'idem-seed-003', 'merchant-seed-3', 42.00, 'EUR', 'AUTHORISED', '', '2026-07-01T13:30:00Z', '2026-07-01T13:30:00Z')
on conflict (authorisation_id) do nothing;

insert into outbox_events (
    event_id,
    version,
    aggregate_type,
    aggregate_id,
    event_type,
    payload,
    status,
    retry_count,
    last_error,
    created_at,
    published_at,
    next_attempt_at,
    idempotency_key,
    correlation_id
)
values
    (
        '99999999-9999-9999-9999-999999999991',
        0,
        'AUTHORISATION',
        'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
        'AUTHORISATION_AUTHORISED',
        '{"authorisationId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","accountId":"11111111-1111-1111-1111-111111111111","amount":25.00,"currencyCode":"GBP","status":"AUTHORISED"}'::jsonb,
        'PUBLISHED',
        0,
        null,
        '2026-07-01T11:00:01Z',
        '2026-07-01T11:00:02Z',
        '2026-07-01T11:00:01Z',
        'idem-seed-001',
        '33333333-3333-3333-3333-333333333333'
    ),
    (
        '99999999-9999-9999-9999-999999999992',
        0,
        'AUTHORISATION',
        'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
        'AUTHORISATION_DECLINED',
        '{"authorisationId":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb","accountId":"22222222-2222-2222-2222-222222222222","amount":80.00,"currencyCode":"USD","status":"DECLINED","failureReason":"Insufficient funds"}'::jsonb,
        'FAILED',
        1,
        'broker timeout',
        '2026-07-01T12:00:01Z',
        null,
        '2026-07-01T12:10:01Z',
        'idem-seed-002',
        '44444444-4444-4444-4444-444444444444'
    ),
    (
        '99999999-9999-9999-9999-999999999993',
        0,
        'AUTHORISATION',
        'cccccccc-cccc-cccc-cccc-cccccccccccc',
        'AUTHORISATION_AUTHORISED',
        '{"authorisationId":"cccccccc-cccc-cccc-cccc-cccccccccccc","accountId":"33333333-3333-3333-3333-333333333333","amount":42.00,"currencyCode":"EUR","status":"AUTHORISED"}'::jsonb,
        'NEW',
        0,
        null,
        '2026-07-01T13:30:01Z',
        null,
        '2026-07-01T13:30:01Z',
        'idem-seed-003',
        '55555555-5555-5555-5555-555555555555'
    )
on conflict (event_id) do nothing;

