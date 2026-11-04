package com.sparxilium.gitstatus.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Prints a {@link StartupCheckException} as Spring Boot's "APPLICATION FAILED TO START" report. */
public class StartupCheckFailureAnalyzer extends AbstractFailureAnalyzer<StartupCheckException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, StartupCheckException cause) {
        return new FailureAnalysis(cause.getMessage(), cause.action(), cause);
    }
}
