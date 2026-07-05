-- Remove the SCRIPT_JAVA task type + in-process Java script adapter (dropped in favour of the
-- sandboxed JavaScript function node). Delete any existing SCRIPT_JAVA tasks and the devices that used
-- that adapter, then tighten the task.type CHECK constraint. Postgres CHECK constraints can't be
-- altered in place, so drop + re-add (mirrors V4__protocol_adapters.sql).

-- 1. Drop data that would violate the new constraint or reference a now-missing adapter.
--    task.device_id is ON DELETE RESTRICT, so tasks must go before their devices; mapping_rule rows
--    cascade from task (ON DELETE CASCADE).
DELETE FROM task
 WHERE type = 'SCRIPT_JAVA'
    OR device_id IN (SELECT id FROM device WHERE adapter_type = 'SCRIPT_JAVA');
DELETE FROM device WHERE adapter_type = 'SCRIPT_JAVA';

-- 2. Re-add the task.type CHECK without SCRIPT_JAVA.
ALTER TABLE task DROP CONSTRAINT task_type_check;
ALTER TABLE task ADD CONSTRAINT task_type_check
    CHECK (type IN ('QUERY', 'FILE_READ', 'API_CALL', 'SHELL',
                    'MQTT_SUBSCRIBE', 'MODBUS_READ', 'TCP_READ'));
