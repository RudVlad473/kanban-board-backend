-- V9__add_columns_color.sql
-- Gives a column an optional visual color, accepted on creation and echoed on every read.
-- (a) Nullable, no DEFAULT, no backfill: existing columns have no meaningful color, and picking
--     one for them (e.g. the design system's accent purple) is a product decision nobody has made
--     -- inventing one here would make this migration a silent product choice rather than a schema
--     change. A client that never sends `color` gets null back, exactly as it does today.
-- (b) No CHECK constraint on the hex format: a CHECK violation resolves through
--     GlobalExceptionHandler.handleDataIntegrityViolation to HTTP 409 with the raw constraint
--     expression as `detail` -- the wrong status code for a client format error, and a small
--     information disclosure. The `#RRGGBB` format is enforced at the DTO boundary instead
--     (ColumnColor, a composed Bean Validation constraint), which produces a 400 with the
--     project's field-error envelope and names no database object. varchar(7) here is a length
--     backstop, not the format authority.

ALTER TABLE columns ADD COLUMN color varchar(7);
