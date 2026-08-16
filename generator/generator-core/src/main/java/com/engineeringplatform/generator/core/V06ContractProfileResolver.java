package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.IntermediateResolutionState;
import com.engineeringplatform.generator.contracts.ResolutionError;
import com.engineeringplatform.generator.contracts.ResolvedFrontend;
import com.engineeringplatform.generator.contracts.ResolverInput;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * V06-WORK-001 — Contract & Profile Resolver.
 *
 * Extends the existing deterministic resolution pipeline (no second Resolver):
 *   - application profile (enterprise certified in V0.6; corporate-portal/ecommerce/custom reserved)
 *   - certified stack profile (enterprise-java25; technology baseline merged into EPM.technology)
 *   - frontend template profiles (enterprise-admin certified; others unsupported -> stable error)
 *
 * Pure computation; source manifests never modified.
 */
public final class V06ContractProfileResolver {

    private static final String STATUS_CERTIFIED = "certified";

    /**
     * Resolves V0.6 application/stack/frontends declarations into the state.
     * Certified status is data-driven from platform.yaml (applicationProfiles /
     * technology.stackProfiles / frontendTemplates).
     */
    public void resolve(ResolverInput input, IntermediateResolutionState.Builder state) {
        resolveApplicationProfile(input, state);
        resolveStackProfile(input, state);
        resolveFrontends(input, state);
    }

    // ---- application profile ----

    private void resolveApplicationProfile(ResolverInput input, IntermediateResolutionState.Builder state) {
        Object application = input.projectManifest().get("application");
        if (!(application instanceof Map<?, ?> app)) {
            return; // not declared -> no constraint (backend-only compatible)
        }
        Object profileVal = app.get("profile");
        if (!(profileVal instanceof String profile)) {
            return;
        }
        Map<String, Object> profiles = asMap(input.platformManifest().get("applicationProfiles"));
        Map<String, Object> entry = asMap(profiles.get(profile));
        if (!STATUS_CERTIFIED.equals(entry.get("status"))) {
            state.error(ResolutionError.constraintViolation(
                    "Application profile '" + profile + "' is reserved/not certified in V0.6 "
                            + "(certified: enterprise)",
                    "project.yaml:/application/profile"));
            return;
        }
        state.applicationProfile(profile);
    }

    // ---- stack profile ----

    private void resolveStackProfile(ResolverInput input, IntermediateResolutionState.Builder state) {
        Object stack = input.projectManifest().get("stack");
        if (!(stack instanceof Map<?, ?> stackMap)) {
            return; // not declared -> platform defaults apply
        }
        Object profileVal = stackMap.get("profile");
        if (!(profileVal instanceof String profile)) {
            return;
        }
        Object technology = input.platformManifest().get("technology");
        Map<String, Object> stackProfiles = asMap(technology instanceof Map<?, ?> t ? t.get("stackProfiles") : null);
        Map<String, Object> entry = asMap(stackProfiles.get(profile));
        if (!STATUS_CERTIFIED.equals(entry.get("status"))) {
            state.error(ResolutionError.constraintViolation(
                    "Stack profile '" + profile + "' is not certified (certified: enterprise-java25)",
                    "project.yaml:/stack/profile"));
            return;
        }
        state.stackProfile(profile);
        // 技术栈由 Profile 管理：把 certified stack 的组件基线合并进 technology
        // （backend/frontend/testing），不散落到 Generator if/else。
        mergeTechnology(input, state, entry, profile);
    }

    private void mergeTechnology(ResolverInput input, IntermediateResolutionState.Builder state,
                                 Map<String, Object> entry, String profile) {
        state.value("technology.stackProfile", profile);
        state.value("technology.stackProfileStatus", STATUS_CERTIFIED);
        for (String section : new String[]{"backend", "frontend", "testing"}) {
            Map<String, Object> sectionMap = asMap(entry.get(section));
            for (Map.Entry<String, Object> e : sectionMap.entrySet()) {
                if (e.getValue() instanceof String || e.getValue() instanceof Number || e.getValue() instanceof Boolean) {
                    state.value("technology." + section + "." + e.getKey(), e.getValue());
                }
            }
        }
    }

    // ---- frontends ----

    private void resolveFrontends(ResolverInput input, IntermediateResolutionState.Builder state) {
        Object frontends = input.projectManifest().get("frontends");
        if (!(frontends instanceof List<?> list)) {
            return; // not declared -> no frontend required (backend-only compatible)
        }
        Map<String, Object> templates = asMap(input.platformManifest().get("frontendTemplates"));
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            Object idVal = m.get("id");
            Object templateVal = m.get("template");
            if (!(idVal instanceof String id) || !(templateVal instanceof String template)) {
                continue;
            }
            Map<String, Object> entry = asMap(templates.get(template));
            String status = entry.get("status") instanceof String s ? s : "reserved";
            if (!STATUS_CERTIFIED.equals(status)) {
                state.error(ResolutionError.constraintViolation(
                        "Frontend template '" + template + "' is not certified in V0.6 "
                                + "(certified: enterprise-admin)",
                        "project.yaml:/frontends/" + id + "/template"));
                continue;
            }
            state.frontend(new ResolvedFrontend(id, template, status));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
