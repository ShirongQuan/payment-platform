-- Fixes a schema mismatch with auth-service (where merchantReference allows up to 128 chars, and
-- authorisation.merchant_reference is varchar(128)): ledger_entry.merchant_reference was only
-- varchar(20), so any AUTHORISATION_AUTHORISED/CAPTURED/REVERSED event carrying a merchant
-- reference longer than 20 chars failed to persist with "value too long for type character
-- varying(20)" (see LedgerEntryEntity#merchantReference / KafkaConsumerConfig error handling -
-- this exception is non-retryable-looking to the caller but was actually a data-loss risk on any
-- valid, API-permitted merchant reference).
--
-- Also widens event_type to varchar(50) to match LedgerEntryEntity's @Column(length = 50) - the
-- longest current EventType value (AUTHORISATION_AUTHORISED, 24 chars) fits in the old varchar(30)
-- today, but the SQL/JPA declarations should agree so future longer event types don't silently
-- reintroduce the same class of bug.

alter table ledger_entry
    alter column merchant_reference type varchar(128);

alter table ledger_entry
    alter column event_type type varchar(50);

