package com.rey.modelquery.tck.src;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.annotations.Aggregate;
import com.rey.modelquery.annotations.AggregateFunction;
import com.rey.modelquery.annotations.GroupBy;
import com.rey.modelquery.annotations.Join;
import com.rey.modelquery.annotations.JoinSource;
import com.rey.modelquery.annotations.PrimaryKey;
import com.rey.modelquery.annotations.QueryModel;
import com.rey.modelquery.annotations.SourceColumn;
import com.rey.modelquery.annotations.SourceModel;
import com.rey.modelquery.annotations.Subtype;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;

/**
 * A {@code @Subtype} column of a model two levels down, whose model joins a source, left out of the select (a selected
 * {@code typeOf} over a joined node is refused): the subtype adapter composes the nested scope with its name map
 * instead of refusing it (AC-GEN-45, R-GEN-43). Compared with native SQL.
 */
class GeneratedNestedSubtypeJoinTest {

    @Entity(name = "NsPlace")
    @Table(name = "ns_place")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    public static class Place {
        @Id public Long id;
    }

    @Entity(name = "NsCampus")
    public static class Campus extends Place {
        public String dean;
    }

    @Entity(name = "NsHolder")
    @Table(name = "ns_holder")
    public static class Holder {
        @Id public Long id;
        @ManyToOne public Place place;
    }

    @Entity(name = "NsVisit")
    @Table(name = "ns_visit")
    public static class Visit {
        @Id public Long id;
        @ManyToOne public Holder holder;
    }

    @SourceModel(root = Place.class)
    public record PlaceCount(@PrimaryKey @GroupBy Long id,
            @Aggregate(fn = AggregateFunction.COUNT, attribute = "id") Long rows) {}

    @QueryModel(root = Place.class)
    @JoinSource(name = "tally", source = PlaceCount.class, on = @JoinSource.On(column = "id", key = "id"))
    public record Spot(@PrimaryKey Long id, @Subtype Class<? extends Place> kind,
            @SourceColumn(join = "tally", field = "rows") Long rows) {}

    @QueryModel(root = Holder.class)
    public record HolderView(@PrimaryKey Long id, @Join Optional<Spot> place) {}

    @QueryModel(root = Visit.class)
    public record VisitView(@PrimaryKey Long id, @Join Optional<HolderView> holder) {}

    @TckTest
    void ac_gen_45_a_subtype_below_a_nested_model_with_a_source_join_reads_the_registered_rows(TckDatabase db)
            throws SQLException {
        List<VisitView> rows = new ArrayList<>();
        try (SessionFactory factory = new Configuration().addAnnotatedClass(Place.class)
                .addAnnotatedClass(Campus.class).addAnnotatedClass(Holder.class).addAnnotatedClass(Visit.class)
                .setProperty(AvailableSettings.JAKARTA_JDBC_URL, db.jdbcUrl())
                .setProperty(AvailableSettings.JAKARTA_JDBC_USER, db.username())
                .setProperty(AvailableSettings.JAKARTA_JDBC_PASSWORD, db.password())
                .setProperty(AvailableSettings.HBM2DDL_AUTO, "create-drop").buildSessionFactory()) {
            factory.inTransaction(this::seed);
            var query = QVisitView.query(QPlaceCount.source().where(f -> f.lte(QPlaceCount.ID, 2L)).build())
                    .select(SelectSet.of(QVisitView.ID, QVisitView.HOLDER_ID, QVisitView.HOLDER_PLACE_ID,
                            QVisitView.HOLDER_PLACE_ROWS))
                    .orderBy(QVisitView.ID.asc()).build();
            factory.inSession(em -> rows.addAll(ModelQueryExecutor.create((EntityManager) em, Visit.class,
                    ModelQueryConfig.defaults()).list(query, Limit.unlimited())));

            assertThat(rows.stream().map(GeneratedNestedSubtypeJoinTest::text))
                    .containsExactlyElementsOf(SourceJoinExecutionTest.rows(db, """
                            select v.id, h.id, p.id, t.n from ns_visit v
                            left join ns_holder h on h.id = v.holder_id
                            left join ns_place p on p.id = h.place_id
                            left join (select id, count(id) n from ns_place where id <= 2 group by id) t
                                on t.id = p.id
                            order by v.id"""));
        }
        // A place, a campus with a source row, a campus without one, a holder with no place and a visit with no
        // holder. The subtype column stays unselected: typeOf over a joined node is MQ4016 (R-COL-36), so the adapter
        // is built and the scope composed on every row, and the column reads null.
        assertThat(rows).hasSize(5).allSatisfy(visit -> assertThat(visit.holder().flatMap(HolderView::place)
                .map(Spot::kind)).isNotPresent());
        assertThat(rows.get(1).holder().orElseThrow().place().orElseThrow().rows()).isEqualTo(1L);
        assertThat(rows.get(2).holder().orElseThrow().place().orElseThrow().rows()).isNull();
        assertThat(rows.get(3).holder().orElseThrow().place()).isEmpty();
        assertThat(rows.get(4).holder()).isEmpty();
    }

    private static String text(VisitView visit) {
        Optional<Spot> spot = visit.holder().flatMap(HolderView::place);
        return SourceJoinExecutionTest.text(visit.id(), visit.holder().map(HolderView::id).orElse(null),
                spot.map(Spot::id).orElse(null), spot.map(Spot::rows).orElse(null));
    }

    private void seed(EntityManager em) {
        Place plain = new Place();
        plain.id = 1L;
        Campus covered = new Campus();
        covered.id = 2L;
        Campus uncovered = new Campus();
        uncovered.id = 3L;
        for (Place place : List.of(plain, covered, uncovered)) {
            em.persist(place);
        }
        long id = 10;
        for (Place place : new Place[] {plain, covered, uncovered, null}) {
            Holder holder = new Holder();
            holder.id = id++;
            holder.place = place;
            em.persist(holder);
        }
        long visit = 100;
        for (long holder : new long[] {10, 11, 12, 13}) {
            Visit row = new Visit();
            row.id = visit++;
            row.holder = em.find(Holder.class, holder);
            em.persist(row);
        }
        Visit orphan = new Visit();
        orphan.id = visit;
        em.persist(orphan);
    }
}
