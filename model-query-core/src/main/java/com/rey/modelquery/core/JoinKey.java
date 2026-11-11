package com.rey.modelquery.core;

import jakarta.persistence.criteria.JoinType;

/**
 * Identity of a join: parent key, attribute, join type and alias (R-COL-02). Never object identity (CC-IMM-04). A root
 * has no parent and no type; its attribute is the entity class name.
 */
record JoinKey(JoinKey parent, String attribute, JoinType type, String alias, boolean entity) {

    JoinKey(JoinKey parent, String attribute, JoinType type, String alias) {
        this(parent, attribute, type, alias, false);
    }

    static JoinKey root(Class<?> entity) {
        return new JoinKey(null, entity.getName(), null, "");
    }

    /** The key of the source {@code model} joined as {@code name}: a root's shape, the join's name as its alias. */
    static JoinKey joinedSource(Class<?> model, String name) {
        return new JoinKey(null, model.getName(), null, name);
    }

    /** An entity join cannot share a key with a root, source join or association join (R-SRC-23). */
    static JoinKey joinedEntity(Class<?> entity, String name) {
        return new JoinKey(null, entity.getName(), null, name, true);
    }

    /** Whether this is a named source or entity join's root: parentless, its alias the join name (R-SRC-21). */
    boolean isJoinedRoot() {
        return parent == null && !alias.isEmpty();
    }

    /** The join this key names, for a message: never the record, whose fields mean nothing to a user (D-215). */
    String describe() {
        if (parent != null && type == null) {
            return parent.describe() + ".treat(" + simpleName() + ")";
        }
        if (parent != null) {
            return "join '" + attribute + "' (" + type + (alias.isEmpty() ? "" : ", alias '" + alias + "'") + ")";
        }
        if (isJoinedRoot()) {
            return "joined " + (entity ? "entity" : "source") + " '" + alias + "'";
        }
        return "root " + simpleName();
    }

    private String simpleName() {
        return attribute.substring(Math.max(attribute.lastIndexOf('.'), attribute.lastIndexOf('$')) + 1);
    }

    /** The parentless key this path starts at. */
    JoinKey root() {
        return parent == null ? this : parent.root();
    }

    /**
     * This key with its root replaced by {@code newRoot}, as {@code TableField.withParent} re-roots a path. A path
     * that starts at a joined root is not re-rooted: its join is no path of the scope, and re-rooting it would read
     * the scope's own column (D-216).
     *
     * @throws IllegalStateException when this path starts at a joined root
     */
    JoinKey reroot(JoinKey newRoot) {
        if (root().isJoinedRoot()) {
            throw new IllegalStateException("a path on the joined root '" + root().alias()
                    + "' cannot be re-rooted; map its join instead (D-216)");
        }
        return replaceRoot(newRoot);
    }

    /** This key with its root, whatever it is, replaced by {@code newRoot}: a joined root mapped to an outer one. */
    JoinKey replaceRoot(JoinKey newRoot) {
        return parent == null ? newRoot : new JoinKey(parent.replaceRoot(newRoot), attribute, type, alias, entity);
    }
}
