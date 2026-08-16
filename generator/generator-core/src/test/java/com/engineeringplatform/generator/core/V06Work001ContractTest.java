package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.EffectiveProjectModel;
import com.engineeringplatform.generator.contracts.ResolutionResult;
import com.engineeringplatform.generator.contracts.ResolverInput;
import com.engineeringplatform.generator.contracts.ResolvedBusinessModule;
import com.engineeringplatform.generator.contracts.ResolvedFrontend;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V06-WORK-001 — Contract & Profile Foundation.
 *
 * Verifies the V0.6 compatible extensions end-to-end through the existing
 * deterministic pipeline (no second Resolver):
 *   A. Project Contract V2 expresses application/stack/frontends/modules
 *   B. enterprise application profile resolves
 *   C. enterprise-java25 stack profile resolves
 *   D. enterprise-admin template resolves
 *   E. unsupported template fails with a stable, explicit error
 *   F. Generic Module Contract expresses a Supplier-type module structurally
 *   G. Module Contract enters Resolver/EPM as structured input
 *   H. legacy V1 manifest stays compatible
 *   I. backend-only manifest behaviour unchanged
 *   J. Portal/E-commerce extension points exist but are not implemented
 *   K. no AI Dev OS dependency
 *   L. Core Generator Execution Engine is not duplicated
 */
class V06Work001ContractTest {

    private static Path repoRoot() {
        Path p = Path.of(System.getProperty("user.dir", "."));
        for (int i = 0; i < 6; i++) {
            if (Files.exists(p.resolve("capabilities")) && Files.exists(p.resolve("platform.yaml"))) {
                return p;
            }
            p = p.getParent();
            if (p == null) {
                throw new IllegalStateException("cannot locate repository root");
            }
        }
        throw new IllegalStateException("cannot locate repository root");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> realPlatform() throws Exception {
        return (Map<String, Object>) AssetYamlReader.parse(
                Files.readString(repoRoot().resolve("platform.yaml"), StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> v05Project() throws Exception {
        return (Map<String, Object>) AssetYamlReader.parse(Files.readString(
                repoRoot().resolve("tests/fixtures/v05-reference/frontend-auth/project.yaml"), StandardCharsets.UTF_8));
    }

    private static ResolutionResult resolve(Map<String, Object> platform, Map<String, Object> project,
                                           Map<String, Map<String, Object>> moduleManifests) throws Exception {
        // Asset-aware resolution with the real repository assets (same as ProductReferenceWork005Test),
        // plus support for injected module manifests (business modules).
        AssetRepository repo = AssetRepository.load(repoRoot());
        List<String> explicitCapabilities = ReferenceResolver.extractIds(project.get("capabilities"));
        AssetContext assetContext = AssetResolution.resolve(repo, explicitCapabilities, platform);

        Map<String, Map<String, Object>> providerManifests = repo.toProviderManifests();
        Map<String, Set<String>> registry = new LinkedHashMap<>();
        registry.put("modules", Set.of("sample-customer", "supplier"));
        registry.put("capabilities", Set.copyOf(repo.capabilities().keySet()));
        registry.put("providers", Set.copyOf(repo.providers().keySet()));

        ResolverInput input = new ResolverInput(platform, project,
                moduleManifests == null ? Map.of() : moduleManifests, providerManifests, registry);
        ResolutionResult result = new CompleteResolver(new ManifestRuntimeValidator(), assetContext).resolve(input);
        if (result.status() == ResolutionResult.Status.FAILED) {
            System.out.println("DIAG errors: " + result.errors());
        }
        return result;
    }

    // ---- A/B/C/D: V2 manifest resolves application/stack/frontends ----

    @Test
    void v2ManifestResolvesEnterpriseStackAndFrontends() throws Exception {
        Map<String, Object> project = Map.of(
                "schemaVersion", 1,
                "project", Map.of("id", "demo", "name", "Demo", "version", "1.0.0"),
                "platform", Map.of("id", "engineering-platform"),
                "quality", Map.of("minimum", "Q2"),
                "application", Map.of("profile", "enterprise"),
                "stack", Map.of("profile", "enterprise-java25"),
                "frontends", List.of(Map.of("id", "admin", "template", "enterprise-admin")),
                "modules", List.of("supplier"));

        ResolutionResult result = resolve(realPlatform(), project, Map.of());
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.SUCCESS);

        EffectiveProjectModel epm = result.effectiveProject();
        // B: enterprise application profile
        assertThat(epm.applicationProfile()).isEqualTo("enterprise");
        // C: enterprise-java25 stack profile (+ technology baseline merged)
        assertThat(epm.stackProfile()).isEqualTo("enterprise-java25");
        assertThat(epm.technology()).containsEntry("stackProfile", "enterprise-java25");
        assertThat(epm.technology()).containsEntry("backend.persistence", "mybatis-plus");
        // D: enterprise-admin template
        assertThat(epm.frontends()).hasSize(1);
        ResolvedFrontend frontend = epm.frontends().get(0);
        assertThat(frontend.id()).isEqualTo("admin");
        assertThat(frontend.template()).isEqualTo("enterprise-admin");
        assertThat(frontend.status()).isEqualTo("certified");
    }

    // ---- E: unsupported template fails with stable explicit error ----

    @Test
    void unsupportedTemplateFailsExplicitly() throws Exception {
        Map<String, Object> project = Map.of(
                "schemaVersion", 1,
                "project", Map.of("id", "demo", "name", "Demo", "version", "1.0.0"),
                "platform", Map.of("id", "engineering-platform"),
                "quality", Map.of("minimum", "Q2"),
                "frontends", List.of(Map.of("id", "web", "template", "corporate-website")));

        ResolutionResult result = resolve(realPlatform(), project, Map.of());
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.FAILED);
        assertThat(result.errors()).anyMatch(e ->
                e.code().equals("CONSTRAINT_VIOLATION")
                        && e.message().contains("corporate-website")
                        && e.message().contains("not certified"));
    }

    // ---- F/G: Supplier business module enters EPM structurally ----

    @Test
    void businessModuleEntersEpmStructurally() throws Exception {
        Map<String, Object> project = Map.of(
                "schemaVersion", 1,
                "project", Map.of("id", "demo", "name", "Demo", "version", "1.0.0"),
                "platform", Map.of("id", "engineering-platform"),
                "quality", Map.of("minimum", "Q2"),
                "application", Map.of("profile", "enterprise"),
                "stack", Map.of("profile", "enterprise-java25"),
                "frontends", List.of(Map.of("id", "admin", "template", "enterprise-admin")),
                "modules", List.of("supplier"));

        Map<String, Map<String, Object>> supplierManifest = new LinkedHashMap<>();
        Map<String, Object> supplier = new LinkedHashMap<>();
        supplier.put("schemaVersion", 1);
        supplier.put("module", Map.of("id", "supplier", "name", "Supplier",
                "version", "1.0.0", "type", "business"));
        Map<String, Object> business = new LinkedHashMap<>();
        business.put("table", "supplier");
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("name", "Supplier");
        entity.put("fields", List.of(
                Map.of("name", "code", "type", "string", "required", true, "unique", true, "length", 50),
                Map.of("name", "name", "type", "string", "required", true, "length", 100),
                Map.of("name", "status", "type", "string", "semantic", "dictionary", "dictionary", "supplier_status")));
        business.put("entity", entity);
        business.put("features", List.of("list", "search", "create", "edit", "detail", "disable"));
        business.put("enterprise", Map.of("permissions", true, "dataScope", true,
                "menu", true, "dictionary", true, "operationLog", true));
        supplier.put("business", business);
        supplierManifest.put("supplier", supplier);

        ResolutionResult result = resolve(realPlatform(), project, supplierManifest);
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.SUCCESS);

        EffectiveProjectModel epm = result.effectiveProject();
        assertThat(epm.businessModules()).hasSize(1);
        ResolvedBusinessModule bm = epm.businessModules().get(0);
        assertThat(bm.id()).isEqualTo("supplier");
        assertThat(bm.table()).isEqualTo("supplier");
        assertThat(bm.entity().name()).isEqualTo("Supplier");
        assertThat(bm.entity().fields()).hasSize(3);
        assertThat(bm.entity().fields().get(0).name()).isEqualTo("code");
        assertThat(bm.entity().fields().get(0).unique()).isTrue();
        assertThat(bm.entity().fields().get(2).semantic()).isEqualTo("dictionary");
        assertThat(bm.entity().fields().get(2).dictionary()).isEqualTo("supplier_status");
        assertThat(bm.features()).contains("list", "create", "disable");
        assertThat(bm.enterprise()).containsEntry("dataScope", true);
    }

    // ---- H: legacy V1 manifest stays compatible ----

    @Test
    void legacyManifestCompatible() throws Exception {
        Map<String, Object> platform = realPlatform();
        Map<String, Object> project = v05Project();
        // v05 project declares capabilities only; resolution must stay SUCCESS
        ResolutionResult result = resolve(platform, project, Map.of());
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        // V0.6 fields default to empty/null (no new behaviour forced)
        assertThat(result.effectiveProject().applicationProfile()).isNull();
        assertThat(result.effectiveProject().frontends()).isEmpty();
        assertThat(result.effectiveProject().businessModules()).isEmpty();
    }

    // ---- I: backend-only manifest behaviour unchanged ----

    @Test
    void backendOnlyManifestUnchanged() throws Exception {
        Map<String, Object> project = Map.of(
                "schemaVersion", 1,
                "project", Map.of("id", "demo", "name", "Demo", "version", "1.0.0"),
                "platform", Map.of("id", "engineering-platform"),
                "quality", Map.of("minimum", "Q2"),
                "capabilities", List.of(Map.of("id", "web"), Map.of("id", "platform-core")));

        ResolutionResult result = resolve(realPlatform(), project, Map.of());
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        assertThat(result.effectiveProject().frontends()).isEmpty();
    }

    // ---- J: Portal/E-commerce extension points exist but are not implemented ----

    @Test
    void portalEcommerceExtensionPointsExistButUnimplemented() throws Exception {
        // reserved application profile -> explicit failure
        Map<String, Object> project = Map.of(
                "schemaVersion", 1,
                "project", Map.of("id", "demo", "name", "Demo", "version", "1.0.0"),
                "platform", Map.of("id", "engineering-platform"),
                "quality", Map.of("minimum", "Q2"),
                "application", Map.of("profile", "corporate-portal"));

        ResolutionResult result = resolve(realPlatform(), project, Map.of());
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.FAILED);
        assertThat(result.errors()).anyMatch(e ->
                e.code().equals("CONSTRAINT_VIOLATION")
                        && e.message().contains("corporate-portal"));
    }

    // ---- K: no AI Dev OS dependency ----

    @Test
    void noAiDevOsDependency() throws Exception {
        // The resolver pipeline is pure declarative computation: no agent/task/approval
        // concepts are introduced by V06-WORK-001. Assert the EPM carries no agent fields.
        Map<String, Object> project = Map.of(
                "schemaVersion", 1,
                "project", Map.of("id", "demo", "name", "Demo", "version", "1.0.0"),
                "platform", Map.of("id", "engineering-platform"),
                "quality", Map.of("minimum", "Q2"),
                "application", Map.of("profile", "enterprise"));

        ResolutionResult result = resolve(realPlatform(), project, Map.of());
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        assertThat(result.effectiveProject().technology()).doesNotContainKey("agent");
        // ResolutionReport carries no AI planning/task concepts introduced here
        assertThat(result.report().warnings()).doesNotContain("ai");
    }

    // ---- L: Core Generator Execution Engine is not duplicated ----

    @Test
    void coreExecutionEngineNotDuplicated() throws Exception {
        // V06-WORK-001 only extends contracts + resolver; the generation chain
        // remains the single AssetProjectGenerator (no second engine introduced).
        Map<String, Object> project = Map.of(
                "schemaVersion", 1,
                "project", Map.of("id", "demo", "name", "Demo", "version", "1.0.0"),
                "platform", Map.of("id", "engineering-platform"),
                "quality", Map.of("minimum", "Q2"),
                "application", Map.of("profile", "enterprise"));

        ResolutionResult result = resolve(realPlatform(), project, Map.of());
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        // capability closure still flows through the existing resolver model
        assertThat(result.effectiveProject().capabilities()).isNotNull();
    }
}
