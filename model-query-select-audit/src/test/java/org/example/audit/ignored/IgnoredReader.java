package org.example.audit.ignored;

import org.example.audit.OtherReader;

public final class IgnoredReader {
    public interface Projection {
        Long getId();
        String getMissing();
    }

    private IgnoredReader() {}

    public static void read(Projection model) {
        model.getId();
        model.getMissing();
    }

    public static void callOther(Projection model) {
        OtherReader.read(model);
    }
}
