package org.example.audit;

import com.rey.modelquery.audit.AuditObserver;
import com.rey.modelquery.audit.AuditProbe;
import java.util.List;
import java.util.Optional;

public final class PlainAuditMain {
    private PlainAuditMain() {}

    public static void main(String[] args) {
        if (com.rey.modelquery.jpa.vendor.HandOffObserver.installed()
                != com.rey.modelquery.jpa.vendor.HandOffObserver.NONE) {
            throw new AssertionError("an inactive audit observer must not be installed (D-242)");
        }
        var observer = new AuditObserver();
        var model = new ManualView(12L);
        observer.handedOff(null, Optional.empty(), List.of(model), List.of());
        observer.handedOff(null, Optional.empty(), List.of(model), List.of());
        if (AuditProbe.isTagged(model)) {
            throw new AssertionError("plain runtime must not tag models without the agent");
        }
    }
}
