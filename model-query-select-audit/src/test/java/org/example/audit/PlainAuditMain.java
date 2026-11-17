package org.example.audit;

import com.rey.modelquery.audit.AuditObserver;
import java.util.List;
import java.util.Optional;

public final class PlainAuditMain {
    private PlainAuditMain() {}

    public static void main(String[] args) {
        var observer = new AuditObserver();
        var model = new ManualView(12L);
        observer.handedOff(null, Optional.empty(), List.of(model), List.of());
        observer.handedOff(null, Optional.empty(), List.of(model), List.of());
        if (AuditObserver.tagOf(model) != null) {
            throw new AssertionError("plain runtime must not tag models without the agent");
        }
    }
}
