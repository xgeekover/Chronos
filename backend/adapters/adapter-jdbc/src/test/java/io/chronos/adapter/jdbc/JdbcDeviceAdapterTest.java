package io.chronos.adapter.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.chronos.api.adapter.TaskType;
import org.junit.jupiter.api.Test;

class JdbcDeviceAdapterTest {

    @Test
    void declaresJdbcTypeAndQueryCapability() {
        JdbcDeviceAdapter adapter = new JdbcDeviceAdapter();
        assertEquals("JDBC", adapter.type());
        assertTrue(adapter.capabilities().supportedTaskTypes().contains(TaskType.QUERY));
    }
}
