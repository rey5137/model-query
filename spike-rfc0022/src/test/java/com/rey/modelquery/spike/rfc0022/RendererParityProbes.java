package com.rey.modelquery.spike.rfc0022;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.core.EntityJoinRenderer;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import org.hibernate.SessionFactory;

final class RendererParityProbes {
    private RendererParityProbes() {}

    static void run(SpikeHarness h, SessionFactory sessions) throws Exception {
        if (!SpikeHarness.HIB.startsWith("7.")) {
            h.notRun("A.3", "portable-vs-hibernate", "A.3 is the 7.4/API 3.2 parity cell; 6.6 keeps API 3.1");
            return;
        }
        ProviderSupport delegate = Q.provider(sessions);
        ProviderSupport withheld = (ProviderSupport) Proxy.newProxyInstance(ProviderSupport.class.getClassLoader(),
                new Class<?>[] {ProviderSupport.class}, (proxy, method, args) -> {
                    if (method.getName().equals("entityJoins")) {
                        return Optional.empty();
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        SpikeHarness.expect(withheld.supports(sessions), "delegation lost supports");
        SpikeHarness.expect(withheld.entityJoins().isEmpty(), "Hibernate renderer was not withheld");
        EntityJoinRenderer portable = withheld.entityJoins().orElse(PortableRenderer.load());
        EntityJoinRenderer reference = delegate.entityJoins().orElseThrow();
        for (Q.Target target : Q.Target.values()) {
            for (JoinKind join : JoinKind.values()) {
                for (boolean filter : new boolean[] {false, true}) {
                    compare(h, sessions, portable, reference, new Q.Options(Q.Anchor.ROOT, join, target, filter));
                }
            }
        }
        for (Q.Anchor anchor : List.of(Q.Anchor.LEFT_PATH, Q.Anchor.INNER_PATH)) {
            for (JoinKind join : JoinKind.values()) {
                compare(h, sessions, portable, reference, new Q.Options(anchor, join, Q.Target.SIMPLE, true));
            }
        }
    }

    private static void compare(SpikeHarness h, SessionFactory sessions, EntityJoinRenderer portable,
            EntityJoinRenderer reference, Q.Options options) {
        for (Q.Kind kind : Q.Kind.values()) {
            h.cell("A.3", options + "/" + kind, () -> {
                List<String> expected = Q.run(sessions, reference, options, kind);
                SpikeHarness.Stmt first = h.lastStatement();
                List<String> actual = Q.run(sessions, portable, options, kind);
                SpikeHarness.Stmt second = h.lastStatement();
                Q.equal(actual, expected);
                SpikeHarness.expect(first.sql().equals(second.sql()), "SQL differs between renderers");
                SpikeHarness.expect(first.binds().equals(second.binds()), "binds differ between renderers");
                Q.equal(actual, Q.nativeRows(h, Q.nativeInvoice(options, kind)));
                return "identical SQL, binds and " + actual.size() + " rows; provider renderer withheld";
            });
        }
    }
}
