package io.chronos.app.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/** Maps the {@code device} table (§D.2). Secrets are stored encrypted in {@link #connectionConfigEnc}. */
@Entity
@Table(name = "device")
public class DeviceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String type; // DATABASE | FILE | HOST | API

    @Column(name = "adapter_type", nullable = false)
    private String adapterType;

    @Column(name = "connection_config_enc")
    private byte[] connectionConfigEnc; // AES-GCM ciphertext (불변 규칙 4)

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "connection_config_meta", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> connectionConfigMeta = new HashMap<>();

    @Column(nullable = false)
    private String status = "UNKNOWN";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getAdapterType() { return adapterType; }
    public void setAdapterType(String adapterType) { this.adapterType = adapterType; }
    @JsonIgnore
    public byte[] getConnectionConfigEnc() { return connectionConfigEnc; }
    public void setConnectionConfigEnc(byte[] v) { this.connectionConfigEnc = v; }
    public Map<String, Object> getConnectionConfigMeta() { return connectionConfigMeta; }
    public void setConnectionConfigMeta(Map<String, Object> v) { this.connectionConfigMeta = v; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
