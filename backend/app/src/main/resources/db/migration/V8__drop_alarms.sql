-- Alarms feature removed. Drop the alarm tables.
-- alarm_event has a FK to alarm_rule, so it must be dropped first.
DROP TABLE IF EXISTS alarm_event;
DROP TABLE IF EXISTS alarm_rule;
