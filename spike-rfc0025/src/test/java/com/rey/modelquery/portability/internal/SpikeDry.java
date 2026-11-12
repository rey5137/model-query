package com.rey.modelquery.portability.internal;

import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.portability.Target;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.function.Function;

/**
 * RFC 0025 spike: {@code DryRun.run(emf, ...)} for one call, keeping the call's whole recording (every JDBC access,
 * not only the first statement) beside production's classification of it. Test classpath only.
 */
public final class SpikeDry {
    private SpikeDry() {}

    /** A classified call and every JDBC access the stub recorded for it. */
    public record Cell(CallResult result, List<String> accesses, Throwable thrown) {}

    /** {@code body} on an executor over {@code emf}, in a transaction rolled back after, classified as DryRun does. */
    public static Cell run(EntityManagerFactory emf, Target target, ModelQueryConfig config, String label,
            Class<?> root, Function<ModelQueryExecutor<Object>, Object> body) {
        Throwable thrown = null;
        try (JdbcRecording recording = JdbcRecording.call()) {
            EntityManager em = emf.createEntityManager();
            try {
                em.getTransaction().begin();
                @SuppressWarnings("unchecked")
                ModelQueryExecutor<Object> executor = ModelQueryExecutor.create(em, (Class<Object>) root,
                        DryRun.harnessConfig(config));
                body.apply(executor);
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable t) {
                thrown = t;
            } finally {
                if (em.getTransaction().isActive()) {
                    em.getTransaction().rollback();
                }
                em.close();
            }
            String provider = emf.getClass().getName().startsWith("org.hibernate.") ? "org.hibernate." : "?";
            return new Cell(DryRun.classify(label, target, false, recording, thrown, provider), recording.accesses(),
                    thrown);
        }
    }

    /** Whether {@code thrown} is, or is caused by, the stub's stop at a statement. */
    public static boolean stoppedAtStatement(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof StubDataSource.StatementReached) {
                return true;
            }
        }
        return false;
    }
}
