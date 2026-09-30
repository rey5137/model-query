package com.rey.modelquery.processor.fixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** No {@code @Access}: property access follows from the id being mapped on a getter. */
@Entity
public class GetterIdEntity {

    private Long key;

    private String text;

    @Id
    public Long getId() {
        return key;
    }

    public void setId(Long id) {
        this.key = id;
    }

    public String getName() {
        return text;
    }

    public void setName(String name) {
        this.text = name;
    }
}
