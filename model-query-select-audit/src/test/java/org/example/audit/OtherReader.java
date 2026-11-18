package org.example.audit;

import org.example.audit.ignored.IgnoredReader.Projection;

public final class OtherReader {
    private OtherReader() {}

    public static void read(Projection model) {
        model.getMissing();
    }
}
