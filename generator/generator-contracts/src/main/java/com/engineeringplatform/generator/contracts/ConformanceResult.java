package com.engineeringplatform.generator.contracts;

import java.util.List;

/**
 * Engineering Conformance result (V02-WORK-005 §2).
 *
 * Independent from V0.1 Task-oriented Verification (ADR-001: Engineering
 * Conformance Verification is an Engineering Platform responsibility; it
 * validates that a generated project matches Platform / Asset standards).
 */
public record ConformanceResult(
        Status status,
        List<ConformanceFinding> findings,
        String summary) {

    public enum Status {
        PASS,
        FAIL
    }

    public ConformanceResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
        status = status == null ? Status.FAIL : status;
    }

    /** Derived status: any ERROR finding -> FAIL. */
    public static ConformanceResult of(List<ConformanceFinding> findings, String summary) {
        List<ConformanceFinding> copy = findings == null ? List.of() : List.copyOf(findings);
        boolean failed = copy.stream().anyMatch(f -> f.severity() == ConformanceSeverity.ERROR);
        return new ConformanceResult(failed ? Status.FAIL : Status.PASS, copy, summary);
    }

    public List<ConformanceFinding> errors() {
        return findings.stream()
                .filter(f -> f.severity() == ConformanceSeverity.ERROR)
                .toList();
    }

    public List<ConformanceFinding> warnings() {
        return findings.stream()
                .filter(f -> f.severity() == ConformanceSeverity.WARNING)
                .toList();
    }
}
