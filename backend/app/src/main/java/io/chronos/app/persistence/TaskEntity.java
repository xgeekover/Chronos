package io.chronos.app.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Maps the {@code task} table (§D.2). Schedule is per-Task (interval or cron). */
@Entity
@Table(name = "task")
public class TaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "node_id", nullable = false)
    private UUID nodeId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(nullable = false)
    private String type; // QUERY | FILE_READ | API_CALL | SHELL | SCRIPT_JAVA

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> definition = new HashMap<>();

    @Column(name = "schedule_kind", nullable = false)
    private String scheduleKind; // INTERVAL | CRON

    @Column(name = "interval_ms")
    private Long intervalMs;

    @Column(name = "cron_expr")
    private String cronExpr;

    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs = 30000;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retry_policy", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> retryPolicy = new HashMap<>();

    @Column(nullable = false)
    private boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getNodeId() { return nodeId; }
    public void setNodeId(UUID nodeId) { this.nodeId = nodeId; }
    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Map<String, Object> getDefinition() { return definition; }
    public void setDefinition(Map<String, Object> definition) { this.definition = definition; }
    public String getScheduleKind() { return scheduleKind; }
    public void setScheduleKind(String scheduleKind) { this.scheduleKind = scheduleKind; }
    public Long getIntervalMs() { return intervalMs; }
    public void setIntervalMs(Long intervalMs) { this.intervalMs = intervalMs; }
    public String getCronExpr() { return cronExpr; }
    public void setCronExpr(String cronExpr) { this.cronExpr = cronExpr; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public Map<String, Object> getRetryPolicy() { return retryPolicy; }
    public void setRetryPolicy(Map<String, Object> retryPolicy) { this.retryPolicy = retryPolicy; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
}
