package com.engineeringplatform.generator.contracts;

/**
 * V06-WORK-001 — Resolved Frontend Template Profile.
 *
 * @param id       frontend id declared in project.frontends (e.g. "admin")
 * @param template frontend template id (e.g. "enterprise-admin")
 * @param status   certified (generatable in V0.6) or reserved (unsupported)
 */
public record ResolvedFrontend(
        String id,
        String template,
        String status) {

    public ResolvedFrontend {
        status = status == null ? "reserved" : status;
    }
}
