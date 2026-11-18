package org.example.audit;

import com.rey.modelquery.annotations.Column;

public class HookIdentityModel {
    @Column
    private String name;

    public String getName() {
        return name;
    }
}
