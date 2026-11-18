package com.rey.modelquery.audit.test;

import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import lombok.EqualsAndHashCode;

@QueryModel(root = AuditOrderEntity.class)
@EqualsAndHashCode
public class AuditOrderBean {
    @PrimaryKey private Long id;
    private String notes;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
