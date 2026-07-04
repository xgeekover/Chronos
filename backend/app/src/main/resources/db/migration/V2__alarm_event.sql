-- Alarm history (§10). One row per time an AlarmRule's condition fired.
CREATE TABLE alarm_event (
    id            BIGSERIAL PRIMARY KEY,
    alarm_rule_id UUID NOT NULL REFERENCES alarm_rule(id) ON DELETE CASCADE,
    tag           TEXT NOT NULL,                 -- canonical tag key
    value         TEXT,                          -- value that triggered it
    severity      TEXT NOT NULL,
    message       TEXT NOT NULL,
    fired_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    acknowledged  BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_alarm_event_time ON alarm_event(fired_at DESC);
CREATE INDEX idx_alarm_event_rule ON alarm_event(alarm_rule_id);
