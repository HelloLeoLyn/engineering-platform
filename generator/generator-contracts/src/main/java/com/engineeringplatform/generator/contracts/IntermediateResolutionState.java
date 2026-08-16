package com.engineeringplatform.generator.contracts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pipeline working state produced by the Resolution Foundation (004B).
 * NOT the final EffectiveProjectModel — it is an intermediate, immutable snapshot
 * consumed by later stages (004C).
 *
 * Extensions (EP-WORK-004C+D): resolved modules/capabilities/providers,
 * quality, environments, compatibility/security findings, quality escalations.
 * All 004B methods are preserved; extensions are additive.
 */
public final class IntermediateResolutionState {

    // ---- 004B core fields ----
    private final Map<String, Object> resolvedValues;
    private final Map<String, Provenance> provenance;
    private final List<ResolvedReference> resolvedReferences;
    private final List<String> activeProfiles;
    private final List<String> warnings;
    private final List<ResolutionError> errors;

    // ---- 004C+D extension fields ----
    private final List<ResolvedModule> resolvedModules;
    private final List<ResolvedCapability> resolvedCapabilities;
    private final List<ResolvedProvider> resolvedProviders;
    private final String quality;
    private final List<ResolutionReport.QualityEscalation> qualityEscalations;
    private final List<String> environments;
    private final List<ResolutionReport.CompatibilityFinding> compatibilityFindings;
    private final List<ResolutionReport.SecurityFinding> securityFindings;
    private final List<String> deprecatedExperimentalAssets;

    // ---- V06-WORK-001: Contract & Profile Foundation ----
    private final String applicationProfile;
    private final String stackProfile;
    private final List<ResolvedFrontend> frontends;
    private final List<ResolvedBusinessModule> businessModules;

    private IntermediateResolutionState(Builder b) {
        this.resolvedValues = Map.copyOf(b.resolvedValues);
        this.provenance = Map.copyOf(b.provenance);
        this.resolvedReferences = List.copyOf(b.resolvedReferences);
        this.activeProfiles = List.copyOf(b.activeProfiles);
        this.warnings = List.copyOf(b.warnings);
        this.errors = List.copyOf(b.errors);
        this.resolvedModules = List.copyOf(b.resolvedModules);
        this.resolvedCapabilities = List.copyOf(b.resolvedCapabilities);
        this.resolvedProviders = List.copyOf(b.resolvedProviders);
        this.quality = b.quality;
        this.qualityEscalations = List.copyOf(b.qualityEscalations);
        this.environments = List.copyOf(b.environments);
        this.compatibilityFindings = List.copyOf(b.compatibilityFindings);
        this.securityFindings = List.copyOf(b.securityFindings);
        this.deprecatedExperimentalAssets = List.copyOf(b.deprecatedExperimentalAssets);
        this.applicationProfile = b.applicationProfile;
        this.stackProfile = b.stackProfile;
        this.frontends = List.copyOf(b.frontends);
        this.businessModules = List.copyOf(b.businessModules);
    }

    // ---- 004B getters ----
    public Map<String, Object> resolvedValues() { return resolvedValues; }
    public Map<String, Provenance> provenance() { return provenance; }
    public List<ResolvedReference> resolvedReferences() { return resolvedReferences; }
    public List<String> activeProfiles() { return activeProfiles; }
    public List<String> warnings() { return warnings; }
    public List<ResolutionError> errors() { return errors; }

    // ---- 004C+D getters ----
    public List<ResolvedModule> resolvedModules() { return resolvedModules; }
    public List<ResolvedCapability> resolvedCapabilities() { return resolvedCapabilities; }
    public List<ResolvedProvider> resolvedProviders() { return resolvedProviders; }
    public String quality() { return quality; }
    public List<ResolutionReport.QualityEscalation> qualityEscalations() { return qualityEscalations; }
    public List<String> environments() { return environments; }
    public List<ResolutionReport.CompatibilityFinding> compatibilityFindings() { return compatibilityFindings; }
    public List<ResolutionReport.SecurityFinding> securityFindings() { return securityFindings; }
    public List<String> deprecatedExperimentalAssets() { return deprecatedExperimentalAssets; }

    // ---- V06-WORK-001 getters ----
    public String applicationProfile() { return applicationProfile; }
    public String stackProfile() { return stackProfile; }
    public List<ResolvedFrontend> frontends() { return frontends; }
    public List<ResolvedBusinessModule> businessModules() { return businessModules; }

    public boolean hasErrors() { return !errors.isEmpty(); }
    public boolean hasFatalErrors() {
        return errors.stream().anyMatch(e -> e.severity() == ResolutionError.Severity.ERROR);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final Map<String, Object> resolvedValues = new LinkedHashMap<>();
        private final Map<String, Provenance> provenance = new LinkedHashMap<>();
        private final List<ResolvedReference> resolvedReferences = new ArrayList<>();
        private final List<String> activeProfiles = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<ResolutionError> errors = new ArrayList<>();
        private final List<ResolvedModule> resolvedModules = new ArrayList<>();
        private final List<ResolvedCapability> resolvedCapabilities = new ArrayList<>();
        private final List<ResolvedProvider> resolvedProviders = new ArrayList<>();
        private String quality;
        private final List<ResolutionReport.QualityEscalation> qualityEscalations = new ArrayList<>();
        private final List<String> environments = new ArrayList<>();
        private final List<ResolutionReport.CompatibilityFinding> compatibilityFindings = new ArrayList<>();
        private final List<ResolutionReport.SecurityFinding> securityFindings = new ArrayList<>();
        private final List<String> deprecatedExperimentalAssets = new ArrayList<>();
        private String applicationProfile;
        private String stackProfile;
        private final List<ResolvedFrontend> frontends = new ArrayList<>();
        private final List<ResolvedBusinessModule> businessModules = new ArrayList<>();

        // ---- 004B builders ----
        public Builder value(String key, Object value) { resolvedValues.put(key, value); return this; }
        public Builder provenance(String key, Provenance p) { provenance.put(key, p); return this; }
        public Builder reference(ResolvedReference r) { resolvedReferences.add(r); return this; }
        public Builder profile(String profile) { activeProfiles.add(profile); return this; }
        public Builder warning(String w) { warnings.add(w); return this; }
        public Builder error(ResolutionError e) { errors.add(e); return this; }
        public Builder errors(List<ResolutionError> es) { errors.addAll(es); return this; }

        public boolean containsValue(String key) { return resolvedValues.containsKey(key); }
        public Object value(String key) { return resolvedValues.get(key); }
        public Provenance provenance(String key) { return provenance.get(key); }

        // ---- 004C+D builders ----
        public Builder module(ResolvedModule m) { resolvedModules.add(m); return this; }
        public Builder capability(ResolvedCapability c) { resolvedCapabilities.add(c); return this; }
        public Builder provider(ResolvedProvider p) { resolvedProviders.add(p); return this; }
        public Builder quality(String q) { this.quality = q; return this; }
        public Builder qualityEscalation(ResolutionReport.QualityEscalation qe) { qualityEscalations.add(qe); return this; }
        public Builder environment(String env) { environments.add(env); return this; }
        public Builder compatibilityFinding(ResolutionReport.CompatibilityFinding cf) { compatibilityFindings.add(cf); return this; }
        public Builder securityFinding(ResolutionReport.SecurityFinding sf) { securityFindings.add(sf); return this; }
        public Builder deprecatedAsset(String asset) { deprecatedExperimentalAssets.add(asset); return this; }

        // ---- V06-WORK-001 builders ----
        public Builder applicationProfile(String profile) { this.applicationProfile = profile; return this; }
        public Builder stackProfile(String profile) { this.stackProfile = profile; return this; }
        public Builder frontend(ResolvedFrontend f) { frontends.add(f); return this; }
        public Builder businessModule(ResolvedBusinessModule m) { businessModules.add(m); return this; }

        public IntermediateResolutionState build() {
            return new IntermediateResolutionState(this);
        }
    }
}
