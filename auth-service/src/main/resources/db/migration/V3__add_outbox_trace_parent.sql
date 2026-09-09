-- Persists the W3C traceparent captured when the outbox row is created (during the original
-- request's transaction), so the async outbox publisher can later link the Kafka producer span
-- back to the originating request trace, even though publishing happens on an unrelated
-- @Scheduled thread with no in-memory trace context.
alter table outbox_event
    add column trace_parent varchar(55);

