package com.rey.modelquery.tck.lock;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Optional;
import org.hibernate.SessionFactory;

/**
 * AC-PRF-10: what Hibernate renders for a pessimistic write and read lock, each wait policy, unjoined and with one
 * inner join, on every Tier-1 vendor, as SQL snapshots (RFC 0009 §1, §3, §4; spec vendor/41, D-127). A plain JPA
 * {@link TypedQuery} set by hand, no {@code ModelQuery} executor path: the M18.2 {@code LockSupport} will do exactly
 * this, and this pins the table it must match. Hibernate 7 renders some rows differently, so the harness keeps a
 * {@code hibernate7/} variant for the line that differs.
 *
 * <p>{@code CustomerEntity} is the root: it has no eager association, so each read renders one statement and the
 * snapshot holds the locking clause alone (an eager {@code @ManyToOne} would add a second select per row).
 *
 * <p>{@code hibernate.query.followOnLocking=false} is set on every query, as RFC §4 says the M18.2 implementation
 * will, so a case Hibernate would otherwise read unlocked fails loudly instead.
 */
class RowLockRenderingTest {

    /** Hibernate's lock-timeout hint: absent waits, {@code 0} does not wait, {@code -2} skips a locked row. */
    private static final String LOCK_TIMEOUT = "jakarta.persistence.lock.timeout";

    private static final String FOLLOW_ON_LOCKING = "hibernate.query.followOnLocking";

    /** An unjoined read, and one inner join, which RFC §3 makes a refused shape rather than a rendered one. */
    private static final List<String> SHAPES = List.of(
            "select c from CustomerEntity c",
            "select c from CustomerEntity c join c.orders o");

    private static final List<LockModeType> MODES =
            List.of(LockModeType.PESSIMISTIC_WRITE, LockModeType.PESSIMISTIC_READ);

    /** WAIT, NO_WAIT and SKIP_LOCKED, as Hibernate's hint values. */
    private static final List<Optional<Integer>> WAIT_POLICIES =
            List.of(Optional.empty(), Optional.of(0), Optional.of(-2));

    @TckTest
    void ac_prf_10_pessimistic_locks_render_the_rfc_table(TckDatabase db) {
        List<String> sql = SqlSnapshots.assertMatches(db, "lock-render", dataSource -> {
            try (SessionFactory sessions = JoinTestSupport.sessionFactory(dataSource)) {
                for (String shape : SHAPES) {
                    for (LockModeType mode : MODES) {
                        for (Optional<Integer> wait : WAIT_POLICIES) {
                            run(sessions, shape, mode, wait);
                        }
                    }
                }
            }
        });

        // One statement per shape x mode x wait policy: a silent follow-on or no-op read would change the count.
        assertThat(sql).hasSize(SHAPES.size() * MODES.size() * WAIT_POLICIES.size());
    }

    private static void run(SessionFactory sessions, String jpql, LockModeType mode, Optional<Integer> wait) {
        sessions.inSession(em -> {
            em.getTransaction().begin();
            try {
                TypedQuery<CustomerEntity> query = em.createQuery(jpql, CustomerEntity.class);
                query.setLockMode(mode);
                query.setHint(FOLLOW_ON_LOCKING, false);
                wait.ifPresent(timeout -> query.setHint(LOCK_TIMEOUT, timeout));
                query.getResultList();
                em.getTransaction().commit();
            } catch (RuntimeException e) {
                em.getTransaction().rollback();
                throw e;
            }
        });
    }
}
