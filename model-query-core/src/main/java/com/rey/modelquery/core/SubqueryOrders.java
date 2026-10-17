package com.rey.modelquery.core;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.EmbeddableType;
import jakarta.persistence.metamodel.IdentifiableType;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.persistence.metamodel.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The inner root's complete id tie-breaker, composite parts ordered by attribute name (R-COL-32). */
final class SubqueryOrders {

    private SubqueryOrders() {}

    static void appendId(Root<?> root, CriteriaBuilder cb, List<Order> orders) {
        append(root, root.getModel(), cb, orders);
    }

    private static void append(Path<?> path, Type<?> type, CriteriaBuilder cb, List<Order> orders) {
        if (type instanceof IdentifiableType<?> entity) {
            attributes(entity).stream().filter(SingularAttribute::isId)
                    .forEach(id -> append(path.get(id.getName()), id.getType(), cb, orders));
        } else if (type instanceof EmbeddableType<?> embedded) {
            attributes(embedded).forEach(part -> append(path.get(part.getName()), part.getType(), cb, orders));
        } else {
            orders.add(cb.asc(path));
        }
    }

    private static List<SingularAttribute<?, ?>> attributes(ManagedType<?> type) {
        var attributes = new ArrayList<SingularAttribute<?, ?>>(type.getSingularAttributes());
        attributes.sort(Comparator.comparing(SingularAttribute::getName));
        return attributes;
    }
}
