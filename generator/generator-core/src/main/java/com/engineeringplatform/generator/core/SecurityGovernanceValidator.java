package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.IntermediateResolutionState;
import com.engineeringplatform.generator.contracts.ResolutionError;
import com.engineeringplatform.generator.contracts.ResolutionReport;
import com.engineeringplatform.generator.contracts.ResolverInput;

import java.util.List;
import java.util.Map;

/**
 * Step 11 — Security / Governance Validation (EP-WORK-004C+D).
 *
 * Only declarative rules are validated. No real security gate, no runtime
 * scan, no external security tools.
 *
 * Rule (minimal, expressible by existing manifests): when
 * platform.governance.securityGate.required is true, the project must
 * declare a security section; otherwise SECURITY_VIOLATION.
 * Findings are recorded into the ResolutionReport.
 * Does NOT reintroduce security.gatePassed.
 */
public final class SecurityGovernanceValidator {

    public void validate(ResolverInput input, IntermediateResolutionState.Builder state) {
        Map<String, Object> platform = input.platformManifest();
        Map<String, Object> project = input.projectManifest();

        boolean securityRequired = isSecurityGateRequired(platform);
        boolean projectDeclaresSecurity = project.get("security") != null;

        if (securityRequired && !projectDeclaresSecurity) {
            String finding = "Platform governance requires security declaration but project has none";
            state.securityFinding(new ResolutionReport.SecurityFinding(
                    "SECURITY_REQUIRED", "ERROR", finding));
            state.error(new ResolutionError(
                    "SECURITY_VIOLATION",
                    finding,
                    ResolutionError.Severity.ERROR,
                    "project-manifest", "project.yaml:/security",
                    null, null, Map.of()));
        } else if (securityRequired) {
            state.securityFinding(new ResolutionReport.SecurityFinding(
                    "SECURITY_OK", "INFO", "Security declaration present"));
        }
    }

    private static boolean isSecurityGateRequired(Map<String, Object> platform) {
        Object governance = platform.get("governance");
        if (governance instanceof Map<?, ?> g && g.get("securityGate") instanceof Map<?, ?> gate) {
            return Boolean.TRUE.equals(gate.get("required"));
        }
        return false;
    }
}
