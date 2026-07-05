package io.chronos.api.adapter;

/** Kind of work a Task performs against its Device (§2). */
public enum TaskType {
    QUERY,
    FILE_READ,
    API_CALL,
    SHELL,
    MQTT_SUBSCRIBE,
    MODBUS_READ,
    TCP_READ
}
