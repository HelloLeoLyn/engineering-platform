package com.engineeringplatform.generator.contracts;

/**
 * Severity level for conformance findings (V02-WORK-005 §14).
 * ERROR leads to overall FAIL; WARNING does not.
 */
public enum ConformanceSeverity {
    ERROR,
    WARNING
}
