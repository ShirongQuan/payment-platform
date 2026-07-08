-- Add lease metadata for outbox publishing claims.

alter table outbox_events
    add column if not exists claimed_at timestamp with time zone,
    add column if not exists claim_until timestamp with time zone;

create index if not exists idx_outbox_events_claim_until
    on outbox_events (status, claim_until);

