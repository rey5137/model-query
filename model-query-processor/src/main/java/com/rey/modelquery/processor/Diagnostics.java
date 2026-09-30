package com.rey.modelquery.processor;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

/**
 * Collects one model's problems: every check reports here and carries on, so a single compilation shows them all, and
 * the model is generated only when nothing was reported (R-DIAG-02, R-DIAG-03).
 */
final class Diagnostics {

    private final Messager messager;
    private int errors;

    Diagnostics(Messager messager) {
        this.messager = messager;
    }

    /** Reports {@code detail} as an error on {@code element}, prefixed with {@code code}. */
    void error(Element element, DiagnosticCode code, String detail) {
        messager.printMessage(Diagnostic.Kind.ERROR, code.code() + ": " + detail, element);
        errors++;
    }

    /** Reports {@code detail} as a warning on {@code element}: the model is still generated. */
    void warning(Element element, DiagnosticCode code, String detail) {
        messager.printMessage(Diagnostic.Kind.WARNING, code.code() + ": " + detail, element);
    }

    boolean hasErrors() {
        return errors > 0;
    }
}
