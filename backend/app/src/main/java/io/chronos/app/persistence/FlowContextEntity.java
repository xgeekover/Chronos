package io.chronos.app.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.HashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Persisted flow runtime context for a scope (currently just "global"). */
@Entity
@Table(name = "flow_context")
public class FlowContextEntity {

    @Id
    private String scope;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> data = new HashMap<>();

    public FlowContextEntity() {}

    public FlowContextEntity(String scope, Map<String, Object> data) {
        this.scope = scope;
        this.data = data;
    }

    public String getScope() {
        return scope;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }
}
