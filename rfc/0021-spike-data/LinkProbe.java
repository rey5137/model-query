import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.criteria.Predicate;
import java.lang.reflect.Method;
import java.util.List;
import org.hibernate.cfg.Configuration;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.JpaEntityJoin;
import org.hibernate.query.criteria.JpaFrom;
import org.hibernate.query.criteria.JpaRoot;
import org.hibernate.query.sqm.tree.SqmJoinType;

/**
 * RFC 0021 spike A.1: compiled once against Hibernate 6.6.58 (javac with the 6.6 jars), then run against 6.6 and
 * against 7.4 without recompiling. "direct" calls bind the 6.6 descriptors; "reflective" is the proposed renderer's way.
 * Entities are nested so one class file serves both runs.
 */
public class LinkProbe {

    @Entity(name = "LA")
    public static class A {
        @Id public Long id;
        public Long ref;
    }

    @Entity(name = "LB")
    public static class B {
        @Id public Long id;
    }

    public static void main(String[] args) throws Exception {
        Configuration cfg = new Configuration();
        cfg.setProperty("hibernate.connection.url", "jdbc:h2:mem:link;DB_CLOSE_DELAY=-1");
        cfg.setProperty("hibernate.connection.username", "sa");
        cfg.setProperty("hibernate.hbm2ddl.auto", "create");
        cfg.addAnnotatedClass(A.class).addAnnotatedClass(B.class);
        var sf = cfg.buildSessionFactory();
        System.out.println("hibernate " + org.hibernate.Version.getVersionString());
        try (var em = sf.createEntityManager()) {
            HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) em.getCriteriaBuilder();

            attempt("direct: JpaFrom#join(Class, SqmJoinType)", () -> {
                JpaCriteriaQuery<Long> q = cb.createQuery(Long.class);
                JpaRoot<A> a = q.from(A.class);
                JpaEntityJoin<B> b = a.join(B.class, SqmJoinType.LEFT);
                return "linked, returned " + b.getClass().getSimpleName();
            });
            attempt("direct: ... then JpaEntityJoin#on(Predicate...)", () -> {
                JpaCriteriaQuery<Long> q = cb.createQuery(Long.class);
                JpaRoot<A> a = q.from(A.class);
                JpaEntityJoin<B> b = a.join(B.class, SqmJoinType.LEFT);
                b.on(cb.equal(b.get("id"), a.get("ref")));
                return "linked";
            });
            attempt("reflective: join looked up once, on looked up on the returned type, run", () -> {
                Method join = JpaFrom.class.getMethod("join", Class.class, SqmJoinType.class);
                JpaCriteriaQuery<Long> q = cb.createQuery(Long.class);
                JpaRoot<A> a = q.from(A.class);
                Object b = join.invoke(a, B.class, SqmJoinType.LEFT);
                Method on = join.getReturnType().getMethod("on", Predicate[].class);
                on.invoke(b, (Object) new Predicate[] {cb.equal(((JpaFrom<?, ?>) b).get("id"), a.get("ref"))});
                q.select(cb.count(a));
                long n = em.createQuery(q).getSingleResult();
                return "runs, count=" + n + ", on declared by " + on.getDeclaringClass().getSimpleName()
                        + " returning " + on.getReturnType().getSimpleName();
            });
        }
        sf.close();
    }

    interface Body {
        String run() throws Exception;
    }

    static void attempt(String what, Body body) {
        try {
            System.out.println("PASS " + what + ": " + body.run());
        } catch (Throwable e) {
            System.out.println("FAIL " + what + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
