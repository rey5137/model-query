package com.rey.modelquery.core;

import jakarta.persistence.criteria.JoinType;

/**
 * Identity of a join: parent key, attribute, join type and alias (R-COL-02). Never object identity (CC-IMM-04). A root
 * has no parent and no type; its attribute is the entity class name.
 */
record JoinKey(JoinKey parent, String attribute, JoinType type, String alias) {

    static JoinKey root(Class<?> entity) {
        return new JoinKey(null, entity.getName(), null, "");
    }
}
