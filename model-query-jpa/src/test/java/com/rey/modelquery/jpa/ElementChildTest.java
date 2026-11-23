package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ChildField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.MapChildField;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.criteria.JoinType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;

class ElementChildTest {
    @Entity(name = "ElementChildOwner")
    static class Owner {
        @Id Long id;
        @ElementCollection List<Element> elements = new ArrayList<>();
        @ElementCollection @MapKeyColumn(name = "m_key") Map<String, Element> mapElements = new HashMap<>();
    }

    @Embeddable
    static class Element {
        String label;
        Element() {}
        Element(String value) { this.label = value; }
    }

    record Row(String value) {}
    record Parent(Long id, List<Row> elements) {}
    record MapParent(Long id, Map<String, Row> mapElements) {}

    private static final TableField<Owner, Owner> ROOT = TableField.root(Owner.class);
    private static final TableField<Element, Element> ELEMENT = TableField.root(Element.class);
    private static final ColumnField<Parent, Owner, Long> ID = ColumnField.of(Parent.class, ROOT, "id", Long.class);
    private static final ColumnField<MapParent, Owner, Long> MAP_ID =
            ColumnField.of(MapParent.class, ROOT, "id", Long.class);
    private static final ColumnField<Row, Element, String> VALUE =
            ColumnField.of(Row.class, ELEMENT, "label", String.class);
    private static final ChildField<Parent, Row> CHILD = new ChildField<>() {
        public String name() { return "elements"; }
        public ColumnField<Parent, ?, ?> key() { return ID; }
        public Optional<ColumnField<Row, ?, ?>> foreignKey() { return Optional.empty(); }
        public Optional<TableField<?, ?>> through() {
            return Optional.of(TableField.<Owner, Element>join(ROOT, "elements", JoinType.INNER));
        }
        public boolean isToMany() { return true; }
        public boolean isElement() { return true; }
        public ModelQuery.Builder<?, ?, Row> query() {
            return ModelQuery.builder(ELEMENT, row -> new Row(row.get(VALUE)));
        }
        public Parent with(Parent parent, List<Row> children) { return new Parent(parent.id(), children); }
    };
    private static final MapChildField<MapParent, String, Row> MAP_CHILD = new MapChildField<>() {
        public String name() { return "mapElements"; }
        public ColumnField<MapParent, ?, ?> key() {
            return MAP_ID;
        }
        public TableField<?, ?> owner() { return ROOT; }
        public String attribute() { return "mapElements"; }
        @Override public Class<String> keyType() { return String.class; }
        @Override public Class<Row> valueType() { return Row.class; }
        public boolean isElementModel() { return true; }
        public ModelQuery.Builder<?, ?, Row> elementQuery() {
            return ModelQuery.builder(ELEMENT, row -> new Row(row.get(VALUE))).select(SelectSet.of(VALUE));
        }
        public MapParent with(MapParent parent, Map<String, Row> entries) {
            return new MapParent(parent.id(), entries);
        }
    };

    @Test
    void ac_fch_38_element_rows_are_handed_off_with_their_own_child_load() {
        var registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:element-child;DB_CLOSE_DELAY=-1")
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop").build();
        try (var sessions = new Configuration().addAnnotatedClass(Owner.class).buildSessionFactory(registry);
                var em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            var owner = new Owner();
            owner.id = 1L;
            owner.elements.add(new Element("Rome"));
            em.persist(owner);
            em.getTransaction().commit();
            var plan = FetchPlan.of(SelectSet.of(ID)).child(CHILD, FetchPlan.of(SelectSet.of(VALUE)));
            var query = ModelQuery.builder(ROOT, row -> new Parent(row.get(ID), List.of()))
                    .primaryKey(PrimaryKey.of(ID)).fetch(plan).build();
            var result = new ArrayList<Parent>();
            var handOffs = HandOffTest.Recording.recording(() -> result.addAll(
                    ModelQueryExecutor.create(em, Owner.class, ModelQueryConfig.defaults())
                            .list(query, Limit.unlimited())));
            assertThat(result.get(0).elements()).containsExactly(new Row("Rome"));
            assertThat(handOffs).hasSize(2);
            assertThat(handOffs.get(0).child()).containsSame(plan.childLoads().get(0));
            assertThat(handOffs.get(0).models()).containsExactly(result.get(0).elements().get(0));
        }
    }

    @Test
    void ac_fch_46_element_model_map_rows_are_handed_off() {
        var registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:element-map-child;DB_CLOSE_DELAY=-1")
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop").build();
        try (var sessions = new Configuration().addAnnotatedClass(Owner.class).buildSessionFactory(registry);
                var em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            var owner = new Owner();
            owner.id = 1L;
            owner.mapElements.put("primary", new Element("Rome"));
            em.persist(owner);
            em.getTransaction().commit();
            var plan = FetchPlan.of(SelectSet.of(MAP_ID)).map(MAP_CHILD);
            var query = ModelQuery.builder(ROOT, row -> new MapParent(row.get(MAP_ID), Map.of()))
                    .primaryKey(PrimaryKey.of(MAP_ID)).fetch(plan).build();
            var result = new ArrayList<MapParent>();
            var handOffs = HandOffTest.Recording.recording(() -> result.addAll(
                    ModelQueryExecutor.create(em, Owner.class, ModelQueryConfig.defaults())
                            .list(query, Limit.unlimited())));
            assertThat(result.get(0).mapElements()).containsEntry("primary", new Row("Rome"));
            assertThat(handOffs).hasSize(2);
            assertThat(handOffs.get(0).models()).containsExactly(result.get(0).mapElements().get("primary"));
        }
    }
}
