package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.BusinessEntityField;
import com.engineeringplatform.generator.contracts.EffectiveProjectModel;
import com.engineeringplatform.generator.contracts.ExecutionResult;
import com.engineeringplatform.generator.contracts.ManifestValidationPort;
import com.engineeringplatform.generator.contracts.ResolutionError;
import com.engineeringplatform.generator.contracts.ResolutionResult;
import com.engineeringplatform.generator.contracts.ResolvedBusinessModule;
import com.engineeringplatform.generator.contracts.ResolvedRelation;
import com.engineeringplatform.generator.contracts.ResolverInput;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V07-WORK-001 — Business Modeling Contract V2.
 *
 * Proves the V0.6 Business Module Contract is extended (not broken):
 *  - A: V0.6 manifest (no relations/reference/advanced semantic) still resolves
 *  - B: V0.7 manifest (reference/enum/money + relations) resolves with full
 *       structured information in EPM
 *  - C: relations are structured Contract — never inferred from field names
 *  - D: invalid contracts (bad relation / bad reference / bad enum) fail with
 *       explicit ERROR resolution errors, never silent fallback
 *  - E: V0.6 manifest still generates exactly as before (compat generation)
 */
class V07Work001ContractV2Test {

    private static final ManifestValidationPort ALWAYS_VALID = new ManifestValidationPort() {
        @Override public boolean isValid(String manifestType, Map<String, Object> manifest) { return true; }
        @Override public List<String> validationErrors(String manifestType, Map<String, Object> manifest) { return List.of(); }
    };

    @TempDir
    Path tempDir;

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
    private static Map<String, Object> readYaml(Path p) throws Exception {
        return (Map<String, Object>) AssetYamlReader.parse(Files.readString(p, StandardCharsets.UTF_8));
    }

    private static Map<String, Map<String, Object>> readModuleManifests(Path modulesDir) throws Exception {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (modulesDir == null || !Files.isDirectory(modulesDir)) {
            return result;
        }
        try (var stream = Files.list(modulesDir)) {
            for (Path f : stream.filter(p -> p.toString().endsWith(".yaml")).sorted().toList()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> manifest = (Map<String, Object>) AssetYamlReader.parse(
                        Files.readString(f, StandardCharsets.UTF_8));
                Map<String, Object> module = asMap(manifest.get("module"));
                String id = String.valueOf(module.get("id"));
                result.put(id, manifest);
            }
        }
        return result;
    }

    private static ResolutionResult resolve(Map<String, Object> project,
                                            Map<String, Map<String, Object>> moduleManifests) throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        List<String> explicitCapabilities = ReferenceResolver.extractIds(project.get("capabilities"));
        AssetContext assetContext = AssetResolution.resolve(repo, explicitCapabilities, realPlatform());
        Map<String, Map<String, Object>> providerManifests = repo.toProviderManifests();
        Map<String, Set<String>> registry = new LinkedHashMap<>();
        registry.put("modules", Set.of("sample-customer", "supplier", "customer-lite", "warehouse-lite",
                "product", "purchase-order", "purchase-order-item"));
        registry.put("capabilities", Set.copyOf(repo.capabilities().keySet()));
        registry.put("providers", Set.copyOf(repo.providers().keySet()));
        ResolverInput input = new ResolverInput(realPlatform(), project,
                moduleManifests == null ? Map.of() : moduleManifests, providerManifests, registry);
        ResolutionResult result = new CompleteResolver(new ManifestRuntimeValidator(), assetContext).resolve(input);
        if (result.status() == ResolutionResult.Status.FAILED) {
            System.out.println("DIAG errors: " + result.errors());
        }
        return result;
    }

    private static AssetProjectGenerator.Options options() {
        return new AssetProjectGenerator.Options(
                "com.acme.core", "v07-ref-demo", "com.acme", "v07-ref-demo", "1.0.0", Map.of());
    }

    private static AssetProjectGenerator.GenerationResult generate(EffectiveProjectModel epm, Path out)
            throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        Files.createDirectories(out);
        return new AssetProjectGenerator().generate(epm, repo, options(), out);
    }

    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    // ---- A: V0.6 compatibility ----

    @Test
    void v06ManifestResolvesWithoutRelations() throws Exception {
        Map<String, Object> project = readYaml(repoRoot().resolve("tests/fixtures/v06-reference/generic/project.yaml"));
        Map<String, Map<String, Object>> manifests = readModuleManifests(
                repoRoot().resolve("tests/fixtures/v06-reference/generic/modules"));
        ResolutionResult result = resolve(project, manifests);
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        EffectiveProjectModel epm = result.effectiveProject();
        assertThat(epm.businessModules()).extracting(ResolvedBusinessModule::id)
                .contains("customer-lite", "warehouse-lite");
        // V0.6 modules carry NO relations, NO reference, NO enum
        for (ResolvedBusinessModule m : epm.businessModules()) {
            assertThat(m.relations()).isEmpty();
            for (BusinessEntityField f : m.entity().fields()) {
                assertThat(f.reference()).isEmpty();
                assertThat(f.enumValues()).isEmpty();
            }
        }
    }

    @Test
    void v06ManifestStillGenerates() throws Exception {
        Map<String, Object> project = readYaml(repoRoot().resolve("tests/fixtures/v06-reference/generic/project.yaml"));
        Map<String, Map<String, Object>> manifests = readModuleManifests(
                repoRoot().resolve("tests/fixtures/v06-reference/generic/modules"));
        EffectiveProjectModel epm = resolve(project, manifests).effectiveProject();
        Path out = tempDir.resolve("gen-v06");
        AssetProjectGenerator.GenerationResult generated = generate(epm, out);
        assertThat(generated.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);
        assertThat(Files.exists(out.resolve("src/main/java/com/acme/core/domain/entity/CustomerLite.java"))).isTrue();
        assertThat(Files.exists(out.resolve("src/main/java/com/acme/core/domain/entity/WarehouseLite.java"))).isTrue();
    }

    // ---- B: V0.7 new semantics ----

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void v07ManifestResolvesRelationsReferencesAndEnums() throws Exception {
        Map<String, Object> project = readYaml(repoRoot().resolve("tests/fixtures/v07-reference/generic/project.yaml"));
        Map<String, Map<String, Object>> manifests = readModuleManifests(
                repoRoot().resolve("tests/fixtures/v07-reference/generic/modules"));
        ResolutionResult result = resolve(project, manifests);
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        EffectiveProjectModel epm = result.effectiveProject();
        assertThat(epm.businessModules()).extracting(ResolvedBusinessModule::id)
                .contains("supplier", "product", "purchase-order", "purchase-order-item");

        ResolvedBusinessModule po = epm.businessModules().stream()
                .filter(m -> m.id().equals("purchase-order")).findFirst().orElseThrow();
        assertThat(po.relations()).hasSize(1);
        ResolvedRelation items = po.relations().get(0);
        assertThat(items.name()).isEqualTo("items");
        assertThat(items.type()).isEqualTo("ONE_TO_MANY");
        assertThat(items.target()).isEqualTo("purchase-order-item");
        assertThat(items.mappedBy()).isEqualTo("purchaseOrderId");
        assertThat(items.composition()).isTrue();

        ResolvedBusinessModule item = epm.businessModules().stream()
                .filter(m -> m.id().equals("purchase-order-item")).findFirst().orElseThrow();
        assertThat(item.relations()).hasSize(1);
        ResolvedRelation poRef = item.relations().get(0);
        assertThat(poRef.name()).isEqualTo("purchaseOrder");
        assertThat(poRef.type()).isEqualTo("MANY_TO_ONE");
        assertThat(poRef.target()).isEqualTo("purchase-order");
        assertThat(poRef.localField()).isEqualTo("purchaseOrderId");
        assertThat(poRef.required()).isTrue();

        // structured reference (never inferred from field name)
        BusinessEntityField supplierId = po.entity().fields().stream()
                .filter(f -> f.name().equals("supplierId")).findFirst().orElseThrow();
        assertThat(supplierId.semantic()).isEqualTo("reference");
        assertThat(supplierId.reference().get("target")).isEqualTo("supplier");
        assertThat(supplierId.reference().get("labelField")).isEqualTo("name");
        assertThat(supplierId.reference().get("searchFields")).isEqualTo(List.of("code", "name"));

        // enum values
        BusinessEntityField status = po.entity().fields().stream()
                .filter(f -> f.name().equals("status")).findFirst().orElseThrow();
        assertThat(status.semantic()).isEqualTo("enum");
        assertThat(status.enumValues()).extracting(e -> e.get("value"))
                .containsExactly("DRAFT", "CONFIRMED", "CLOSED");

        // money type
        BusinessEntityField totalAmount = po.entity().fields().stream()
                .filter(f -> f.name().equals("totalAmount")).findFirst().orElseThrow();
        assertThat(totalAmount.type()).isEqualTo("money");
        assertThat(totalAmount.precision()).isEqualTo(14);
        assertThat(totalAmount.scale()).isEqualTo(2);
    }

    // ---- C: relations are structured, not inferred ----

    @Test
    void fieldNameAloneNeverCreatesRelations() throws Exception {
        // purchase-order-item has productId — no relation declared -> NO relation is created
        Map<String, Object> project = readYaml(repoRoot().resolve("tests/fixtures/v07-reference/generic/project.yaml"));
        Map<String, Map<String, Object>> manifests = readModuleManifests(
                repoRoot().resolve("tests/fixtures/v07-reference/generic/modules"));
        EffectiveProjectModel epm = resolve(project, manifests).effectiveProject();
        ResolvedBusinessModule item = epm.businessModules().stream()
                .filter(m -> m.id().equals("purchase-order-item")).findFirst().orElseThrow();
        // productId is a reference FIELD, not a relation; only the declared purchaseOrder relation exists
        assertThat(item.relations()).extracting(ResolvedRelation::name).containsExactly("purchaseOrder");
        BusinessEntityField productId = item.entity().fields().stream()
                .filter(f -> f.name().equals("productId")).findFirst().orElseThrow();
        assertThat(productId.reference().get("target")).isEqualTo("product");
    }

    // ---- D: invalid contracts fail explicitly ----

    @Test
    void duplicateRelationNameFails() throws Exception {
        Map<String, Object> manifest = businessManifest(List.of(
                Map.of("name", "supplierId", "type", "integer")));
        @SuppressWarnings("unchecked")
        Map<String, Object> business = (Map<String, Object>) manifest.get("business");
        business.put("relations", List.of(
                Map.of("name", "dup", "type", "MANY_TO_ONE", "target", "supplier", "localField", "supplierId"),
                Map.of("name", "dup", "type", "MANY_TO_ONE", "target", "supplier", "localField", "supplierId")));
        assertThat(resolveErrors(manifestsWith("bad-module", manifest)))
                .anyMatch(e -> e.code().equals("RELATION_NAME_DUPLICATE"));
    }

    @Test
    void unknownRelationTypeFails() throws Exception {
        assertThat(resolveErrors(relationManifest(
                "FOO", "supplier", "supplierId", null)))
                .anyMatch(e -> e.code().equals("RELATION_TYPE_UNKNOWN"));
    }

    @Test
    void manyToManyExplicitlyUnsupported() throws Exception {
        assertThat(resolveErrors(relationManifest(
                "MANY_TO_MANY", "supplier", "supplierId", null)))
                .anyMatch(e -> e.code().equals("RELATION_TYPE_UNSUPPORTED"));
    }

    @Test
    void unknownRelationTargetFails() throws Exception {
        assertThat(resolveErrors(relationManifest(
                "MANY_TO_ONE", "no-such-module", "supplierId", null)))
                .anyMatch(e -> e.code().equals("RELATION_TARGET_UNKNOWN"));
    }

    @Test
    void unknownLocalFieldFails() throws Exception {
        assertThat(resolveErrors(relationManifest(
                "MANY_TO_ONE", "supplier", "noSuchField", null)))
                .anyMatch(e -> e.code().equals("RELATION_LOCAL_FIELD_UNKNOWN"));
    }

    @Test
    void unknownMappedByFails() throws Exception {
        assertThat(resolveErrors(relationManifest(
                "ONE_TO_MANY", "purchase-order-item", null, "noSuchField")))
                .anyMatch(e -> e.code().equals("RELATION_MAPPED_BY_UNKNOWN"));
    }

    @Test
    void referenceSemanticRequiresConfig() throws Exception {
        Map<String, Object> manifest = businessManifest(List.of(
                Map.of("name", "supplierId", "type", "integer", "semantic", "reference")));
        assertThat(resolveErrors(manifestsWith("bad-module", manifest)))
                .anyMatch(e -> e.code().equals("REFERENCE_CONFIG_REQUIRED"));
    }

    @Test
    void referenceConfigOnNonReferenceFails() throws Exception {
        Map<String, Object> manifest = businessManifest(List.of(Map.of(
                "name", "supplierId", "type", "integer",
                "reference", Map.of("target", "supplier"))));
        assertThat(resolveErrors(manifestsWith("bad-module", manifest)))
                .anyMatch(e -> e.code().equals("REFERENCE_CONFIG_ON_NON_REFERENCE"));
    }

    @Test
    void referenceTargetMustExist() throws Exception {
        Map<String, Object> manifest = businessManifest(List.of(Map.of(
                "name", "supplierId", "type", "integer", "semantic", "reference",
                "reference", Map.of("target", "no-such-module"))));
        assertThat(resolveErrors(manifestsWith("bad-module", manifest)))
                .anyMatch(e -> e.code().equals("REFERENCE_TARGET_UNKNOWN"));
    }

    @Test
    void enumTypeRequiresValues() throws Exception {
        Map<String, Object> manifest = businessManifest(List.of(Map.of(
                "name", "status", "type", "enum")));
        assertThat(resolveErrors(manifestsWith("bad-module", manifest)))
                .anyMatch(e -> e.code().equals("ENUM_VALUES_REQUIRED"));
    }

    // ---- helpers ----

    private static List<ResolutionError> resolveErrors(Map<String, Map<String, Object>> manifests) throws Exception {
        Map<String, Object> project = Map.of(
                "schemaVersion", 1,
                "project", Map.of("id", "err-demo", "name", "Err Demo", "version", "1.0.0"),
                "platform", Map.of("id", "engineering-platform"),
                "modules", List.of("bad-module"));
        ResolutionResult result = resolve(project, manifests);
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.FAILED);
        return result.errors();
    }

    private static Map<String, Map<String, Object>> relationManifest(Object... relArgs) {
        List<Map<String, Object>> relations = new ArrayList<>();
        for (int i = 0; i < relArgs.length; i += 4) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", "rel" + (i / 4));
            r.put("type", relArgs[i]);
            r.put("target", relArgs[i + 1]);
            if (relArgs[i + 2] != null) r.put("localField", relArgs[i + 2]);
            if (relArgs[i + 3] != null) r.put("mappedBy", relArgs[i + 3]);
            relations.add(r);
        }
        Map<String, Object> manifest = businessManifest(List.of(
                Map.of("name", "supplierId", "type", "integer")));
        @SuppressWarnings("unchecked")
        Map<String, Object> business = (Map<String, Object>) manifest.get("business");
        business.put("relations", relations);
        return manifestsWith("bad-module", manifest);
    }

    private static Map<String, Object> businessManifest(List<Map<String, Object>> fields) {
        Map<String, Object> business = new LinkedHashMap<>();
        business.put("table", "bad_module");
        business.put("entity", Map.of("name", "BadModule", "fields", fields));
        business.put("features", List.of("list"));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("module", Map.of("id", "bad-module", "name", "BadModule", "version", "1.0.0", "type", "business"));
        manifest.put("compatibility", Map.of("platformVersion", "0.6"));
        manifest.put("business", business);
        return manifest;
    }

    private static Map<String, Map<String, Object>> manifestsWith(String id, Map<String, Object> manifest) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        // provide real target modules for cross-module validation
        try {
            Map<String, Map<String, Object>> real = readModuleManifests(
                    repoRoot().resolve("tests/fixtures/v07-reference/generic/modules"));
            out.putAll(real);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        out.put(id, manifest);
        return out;
    }
}
