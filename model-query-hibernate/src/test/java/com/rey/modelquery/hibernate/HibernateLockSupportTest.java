package com.rey.modelquery.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.RowLock;
import com.rey.modelquery.core.RowLock.Strength;
import com.rey.modelquery.core.RowLock.Wait;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.TypedQuery;
import java.util.List;
import org.hibernate.LockMode;
import org.hibernate.LockOptions;
import org.hibernate.SessionFactory;
import org.hibernate.Version;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.query.Query;
import org.junit.jupiter.api.Test;

/**
 * {@link HibernateLockSupport}: what it reports as rendered, per dialect x strength x wait, and what {@code apply} sets
 * on a statement (spec vendor/40 R-VND-15, vendor/41 §6; D-127).
 *
 * <p>The H2 expectations differ between the two Hibernate lines, as the {@code lock-render} snapshots show
 * ({@code sql/h2/lock-render.sql} and its {@code hibernate7/} variant), so this test reads the running major version
 * and asks the dialect the running database built, as the engine does.
 */
class HibernateLockSupportTest {

    @Entity(name = "Party")
    static class Party {
        @Id
        Long id;

        @Column(name = "name")
        String name;
    }

    private static final HibernateLockSupport SUPPORT = new HibernateLockSupport();

    /** Every strength x wait combination, so a combination cannot pass unnoticed. */
    private static final List<RowLock> LOCKS = List.of(
            RowLock.exclusive(),
            RowLock.exclusive(Wait.NO_WAIT),
            RowLock.exclusive(Wait.SKIP_LOCKED),
            RowLock.shared(),
            RowLock.shared(Wait.NO_WAIT),
            RowLock.shared(Wait.SKIP_LOCKED));

    // ---- rendered

    @Test
    void ac_vnd_13_rendered_accepts_every_combination_on_postgresql_and_mysql() {
        for (Dialect dialect : List.of(new PostgreSQLDialect(), new MySQLDialect())) {
            assertThat(LOCKS).allSatisfy(lock -> assertThat(HibernateLockSupport.isRendered(dialect, lock))
                    .withFailMessage("%s on %s", lock, dialect.getClass().getSimpleName())
                    .isTrue());
        }
    }

    @Test
    void ac_vnd_13_rendered_refuses_the_waits_h2_drops_for_each_strength() {
        try (SessionFactory sessions = factory("rendered")) {
            Dialect h2 = dialect(sessions);
            assertThat(HibernateLockSupport.isRendered(h2, RowLock.exclusive())).isTrue();
            assertThat(HibernateLockSupport.isRendered(h2, RowLock.shared())).isTrue();
            // 6.6 emits a plain FOR UPDATE for either wait; 7.x emits both for exclusive() only. Inside a factory, as
            // the engine asks it, so the dialect is the one the running database's version built.
            boolean hibernate7 = majorVersion() >= 7;
            for (Wait wait : List.of(Wait.NO_WAIT, Wait.SKIP_LOCKED)) {
                assertThat(HibernateLockSupport.isRendered(h2, RowLock.exclusive(wait))).isEqualTo(hibernate7);
                assertThat(SUPPORT.rendered(sessions, RowLock.exclusive(wait))).isEqualTo(hibernate7);
                // shared() is widened to FOR UPDATE on H2, whose read clause carries no wait token on either line.
                assertThat(HibernateLockSupport.isRendered(h2, RowLock.shared(wait))).isFalse();
                assertThat(SUPPORT.rendered(sessions, RowLock.shared(wait))).isFalse();
            }
        }
    }

    // ---- apply

    @Test
    void ac_vnd_13_apply_sets_the_mode_the_wait_timeout_and_follow_on_locking_for_every_combination() {
        try (SessionFactory sessions = factory("apply")) {
            sessions.inSession(em -> {
                for (RowLock lock : LOCKS) {
                    TypedQuery<Party> query = em.createQuery("select p from Party p", Party.class);
                    SUPPORT.apply(query, lock);
                    Query<?> hibernate = query.unwrap(Query.class);
                    assertThat(hibernate.getLockOptions().getLockMode()).withFailMessage("%s", lock)
                            .isEqualTo(lock.strength() == Strength.EXCLUSIVE ? LockMode.PESSIMISTIC_WRITE
                                    : LockMode.PESSIMISTIC_READ);
                    assertThat(hibernate.getLockOptions().getTimeOut()).withFailMessage("%s", lock)
                            .isEqualTo(timeout(lock));
                    assertThat(hibernate.getHints()).containsEntry("hibernate.query.followOnLocking", false);
                }
            });
        }
    }

    /** The lock timeout Hibernate's {@link LockOptions} holds for {@code lock}'s wait policy. */
    private static int timeout(RowLock lock) {
        return switch (lock.waitPolicy()) {
            case NO_WAIT -> LockOptions.NO_WAIT;
            case SKIP_LOCKED -> LockOptions.SKIP_LOCKED;
            case WAIT -> LockOptions.WAIT_FOREVER;
        };
    }

    /** The dialect {@code sessions} runs on, the one {@link HibernateLockSupport#rendered} asks. */
    private static Dialect dialect(SessionFactory sessions) {
        return ((SessionFactoryImplementor) sessions).getJdbcServices().getDialect();
    }

    /** The running Hibernate's major version. */
    private static int majorVersion() {
        return Integer.parseInt(Version.getVersionString().split("\\.")[0]);
    }

    private static SessionFactory factory(String name) {
        StandardServiceRegistryBuilder registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:lock-" + name)
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "")
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop");
        return new Configuration().addAnnotatedClass(Party.class).buildSessionFactory(registry.build());
    }
}
