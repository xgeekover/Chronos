package io.chronos.app.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

/** One entry in the flow runtime audit trail ({@code flow_audit}). */
@Entity
@Table(name = "flow_audit")
public class FlowAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    @Column(name = "at", nullable = false, updatable = false)
    private Instant at;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private String action; // DEPLOY | STOP | ERROR

    @Column(name = "flow_name")
    private String flowName;

    @Column(name = "node_count")
    private Integer nodeCount;

    private String detail;

    public FlowAuditEntity() {}

    public FlowAuditEntity(String actor, String action, String flowName, Integer nodeCount, String detail) {
        this.actor = actor;
        this.action = action;
        this.flowName = flowName;
        this.nodeCount = nodeCount;
        this.detail = detail;
    }

    public Long getId() {
        return id;
    }

    public Instant getAt() {
        return at;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getFlowName() {
        return flowName;
    }

    public Integer getNodeCount() {
        return nodeCount;
    }

    public String getDetail() {
        return detail;
    }
}
