-- VictorSpoof backend schema (Postgres). Applied idempotently at startup.
CREATE TABLE IF NOT EXISTS accounts (
    id               UUID PRIMARY KEY,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    age_attested_18  BOOLEAN NOT NULL,
    verified_number  TEXT,              -- user's OWN number, set only after OTP verification
    verified_at      TIMESTAMPTZ,
    status           TEXT NOT NULL DEFAULT 'active'   -- active | suspended
);

CREATE TABLE IF NOT EXISTS number_pool (
    id            SERIAL PRIMARY KEY,
    e164          TEXT NOT NULL UNIQUE,   -- VictorSpoof-owned number
    cnam_label    TEXT,                   -- fun CNAM, pre-approved and name-filtered
    cnam_supported BOOLEAN NOT NULL DEFAULT false,
    active        BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE IF NOT EXISTS aup_log (
    id           BIGSERIAL PRIMARY KEY,
    account_id   UUID NOT NULL REFERENCES accounts(id),
    aup_version  TEXT NOT NULL,
    accepted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip           TEXT,
    user_agent   TEXT
);

CREATE TABLE IF NOT EXISTS rate_limits (
    id           BIGSERIAL PRIMARY KEY,
    account_id   UUID NOT NULL REFERENCES accounts(id),
    target_e164  TEXT NOT NULL,
    day_utc      DATE NOT NULL,
    placed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS rate_limits_acct_day ON rate_limits(account_id, day_utc);
CREATE INDEX IF NOT EXISTS rate_limits_target_day ON rate_limits(account_id, target_e164, day_utc);

CREATE TABLE IF NOT EXISTS blocked_numbers (
    e164      TEXT PRIMARY KEY,
    category  TEXT NOT NULL        -- government | police | hospital | bank | emergency
);
