-- New protocol source adapters (MQTT / Modbus TCP / TCP socket). Extend the device.type and
-- task.type CHECK constraints to accept their categories + task types. Postgres CHECK constraints
-- can't be altered in place, so drop + re-add (additive; existing rows are unaffected).

ALTER TABLE device DROP CONSTRAINT device_type_check;
ALTER TABLE device ADD CONSTRAINT device_type_check
    CHECK (type IN ('DATABASE', 'FILE', 'HOST', 'API', 'MQTT', 'MODBUS', 'TCP'));

ALTER TABLE task DROP CONSTRAINT task_type_check;
ALTER TABLE task ADD CONSTRAINT task_type_check
    CHECK (type IN ('QUERY', 'FILE_READ', 'API_CALL', 'SHELL', 'SCRIPT_JAVA',
                    'MQTT_SUBSCRIBE', 'MODBUS_READ', 'TCP_READ'));
