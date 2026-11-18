package com.rey.modelquery.audit;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

/** Tells {@link CurrentTest} which test method runs on each thread, so reports name the real test (R-INS-21). */
final class AuditTestListener implements TestExecutionListener {

    @Override
    public void executionStarted(TestIdentifier identifier) {
        if (identifier.getSource().orElse(null) instanceof MethodSource source) {
            CurrentTest.started(identifier.getUniqueId(), source.getClassName(), source.getMethodName());
        }
    }

    @Override
    public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
        CurrentTest.finished(identifier.getUniqueId());
    }
}
