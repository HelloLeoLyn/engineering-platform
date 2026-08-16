package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.ConformanceResult;
import com.engineeringplatform.generator.contracts.EffectiveProjectModel;
import com.engineeringplatform.generator.contracts.ExecutionResult;
import com.engineeringplatform.generator.contracts.ManifestValidationPort;
import com.engineeringplatform.generator.contracts.ResolutionResult;
import com.engineeringplatform.generator.contracts.ResolvedBusinessModule;
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
 * V06-WORK-002B — Contract-driven Generic Module Generator (A-M acceptance).
 *
 * Proves that EPM.businessModules[] alone drives full-stack generation: no
 * per-entity *-reference capability, no name-based branch, no hardcoded
 * frontend-auth registration. CustomerLite + WarehouseLite are Contract-only
 * genericity proofs; Supplier migrates through the Generic Generator.
 */
class V06Work002BGenericModuleTest {

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
        registry.put("modules", Set.of("sample-customer", "supplier", "customer-lite", "warehouse-lite"));
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
                "com.acme.core", "generic-demo", "com.acme", "generic-demo", "1.0.0", Map.of());
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

    // ---- A: EPM.businessModules drives generation ----

    @Test
    void epmBusinessModulesDriveGeneration() throws Exception {
        Map<String, Object> project = readYaml(repoRoot().resolve("tests/fixtures/v06-reference/generic/project.yaml"));
        Map<String, Map<String, Object>> manifests = readModuleManifests(
                repoRoot().resolve("tests/fixtures/v06-reference/generic/modules"));
        ResolutionResult result = resolve(project, manifests);
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        EffectiveProjectModel epm = result.effectiveProject();
        assertThat(epm.businessModules()).hasSize(2);
        assertThat(epm.businessModules()).extracting(ResolvedBusinessModule::id)
                .contains("customer-lite", "warehouse-lite");

        Path out = tempDir.resolve("gen-a");
        AssetProjectGenerator.GenerationResult generated = generate(epm, out);
        assertThat(generated.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);
        assertThat(Files.exists(out.resolve("src/main/java/com/acme/core/domain/entity/CustomerLite.java"))).isTrue();
        assertThat(Files.exists(out.resolve("src/main/java/com/acme/core/domain/entity/WarehouseLite.java"))).isTrue();
    }

    // ---- B: Generic backend CRUD generation ----

    @Test
    void genericBackendCrudGeneration() throws Exception {
        Map<String, Object> project = readYaml(repoRoot().resolve("tests/fixtures/v06-reference/generic/project.yaml"));
        Map<String, Map<String, Object>> manifests = readModuleManifests(
                repoRoot().resolve("tests/fixtures/v06-reference/generic/modules"));
        EffectiveProjectModel epm = resolve(project, manifests).effectiveProject();
        Path out = tempDir.resolve("gen-b");
        generate(epm, out);

        for (String f : List.of(
                "src/main/java/com/acme/core/domain/entity/CustomerLite.java",
                "src/main/java/com/acme/core/application/customerlite/CustomerLitePort.java",
                "src/main/java/com/acme/core/application/customerlite/CustomerLiteService.java",
                "src/main/java/com/acme/core/application/customerlite/CustomerLiteCreateRequest.java",
                "src/main/java/com/acme/core/application/customerlite/CustomerLiteUpdateRequest.java",
                "src/main/java/com/acme/core/application/customerlite/CustomerLiteResponse.java",
                "src/main/java/com/acme/core/infrastructure/persistence/mapper/CustomerLiteMapper.java",
                "src/main/java/com/acme/core/infrastructure/persistence/MybatisCustomerLiteRepository.java",
                "src/main/java/com/acme/core/infrastructure/persistence/CustomerLiteBeansConfig.java",
                "src/main/java/com/acme/core/api/customerlite/CustomerLiteController.java",
                "src/main/resources/db/migration/V100__customer_lite.sql",
                "src/test/resources/db/seed/seed-zzz-customer-lite.sql",
                "src/test/java/com/acme/core/CustomerLiteModelUnitTest.java")) {
            assertThat(Files.exists(out.resolve(f))).as(f).isTrue();
        }
        // warehouse-lite gets its own migration version (deterministic per sorted index)
        assertThat(Files.exists(out.resolve("src/main/resources/db/migration/V101__warehouse_lite.sql"))).isTrue();
    }

    // ---- C: Generic frontend CRUD generation ----

    @Test
    void genericFrontendCrudGeneration() throws Exception {
        Map<String, Object> project = readYaml(repoRoot().resolve("tests/fixtures/v06-reference/generic/project.yaml"));
        Map<String, Map<String, Object>> manifests = readModuleManifests(
                repoRoot().resolve("tests/fixtures/v06-reference/generic/modules"));
        EffectiveProjectModel epm = resolve(project, manifests).effectiveProject();
        Path out = tempDir.resolve("gen-c");
        generate(epm, out);

        for (String f : List.of(
                "frontend/src/types/customer-lite.ts",
                "frontend/src/api/customer-lite.ts",
                "frontend/src/router/business/customer-lite.ts",
                "frontend/src/views/customerlite/CustomerLiteListView.vue",
                "frontend/src/views/customerlite/CustomerLiteDetailView.vue",
                "frontend/src/views/customerlite/CustomerLiteEditView.vue",
                "frontend/tests/customer-lite-api.spec.ts",
                "frontend/tests/customer-lite-route.spec.ts",
                "frontend/tests/customer-lite-list.spec.ts",
                "frontend/tests/customer-lite-detail.spec.ts")) {
            assertThat(Files.exists(out.resolve(f))).as(f).isTrue();
        }
    }

    // ---- D: contract fields drive generated fields ----

    @Test
    void contractFieldsDriveGeneratedFields() throws Exception {
        Map<String, Object> project = readYaml(repoRoot().resolve("tests/fixtures/v06-reference/generic/project.yaml"));
        Map<String, Map<String, Object>> manifests = readModuleManifests(
                repoRoot().resolve("tests/fixtures/v06-reference/generic/modules"));
        EffectiveProjectModel epm = resolve(project, manifests).effectiveProject();
        Path out = tempDir.resolve("gen-d");
        generate(epm, out);

        String customer = Files.readString(
                out.resolve("src/main/java/com/acme/core/domain/entity/CustomerLite.java"), StandardCharsets.UTF_8);
        assertThat(customer).contains("phone").contains("private String phone;");
        String warehouse = Files.readString(
                out.resolve("src/main/java/com/acme/core/domain/entity/WarehouseLite.java"), StandardCharsets.UTF_8);
        assertThat(warehouse).contains("manager").contains("private String manager;");
        // distinct domains -> distinct entities (not copy-paste)
        assertThat(customer).doesNotContain("manager");
        assertThat(warehouse).doesNotContain("phone");
    }

    // ---- E: contract CRUD features drive generated capabilities ----

    @Test
    void contractFeaturesDriveCapabilities() throws Exception {
        // customer-lite declares full CRUD features; build a minimal read-only variant
        Map<String, Object> project = readYaml(repoRoot().resolve("tests/fixtures/v06-reference/generic/project.yaml"));
        Map<String, Map<String, Object>> manifests = readModuleManifests(
                repoRoot().resolve("tests/fixtures/v06-reference/generic/modules"));
        EffectiveProjectModel epm = resolve(project, manifests).effectiveProject();
        Path out = tempDir.resolve("gen-e");
        generate(epm, out);

        String controller = Files.readString(
                out.resolve("src/main/java/com/acme/core/api/customerlite/CustomerLiteController.java"), StandardCharsets.UTF_8);
        assertThat(controller).contains("@PostMapping").contains("@PutMapping").contains("/disable");

        // read-only module: no create/update/disable endpoints
        Map<String, Object> roProject = new LinkedHashMap<>();
        roProject.put("schemaVersion", 1);
        roProject.put("project", Map.of("id", "ro-demo", "name", "RO Demo", "version", "1.0.0",
                "basePackage", "com.acme.core"));
        roProject.put("platform", Map.of("id", "engineering-platform"));
        roProject.put("quality", Map.of("minimum", "Q2"));
        roProject.put("modules", List.of("customer-lite"));
        Map<String, Map<String, Object>> roManifests = new LinkedHashMap<>(manifests);
        Map<String, Object> roModule = new LinkedHashMap<>();
        roModule.put("schemaVersion", 1);
        roModule.put("module", Map.of("id", "customer-lite", "name", "CustomerLite", "version", "1.0.0", "type", "business"));
        Map<String, Object> roBusiness = new LinkedHashMap<>();
        roBusiness.put("table", "customer_lite");
        Map<String, Object> roEntity = new LinkedHashMap<>();
        roEntity.put("name", "CustomerLite");
        roEntity.put("fields", List.of(
                Map.of("name", "code", "type", "string", "required", true, "unique", true, "length", 50),
                Map.of("name", "name", "type", "string", "required", true, "length", 100)));
        roBusiness.put("entity", roEntity);
        roBusiness.put("features", List.of("list", "detail"));
        roModule.put("business", roBusiness);
        roManifests.put("customer-lite", roModule);
        EffectiveProjectModel roEpm = resolve(roProject, roManifests).effectiveProject();
        Path roOut = tempDir.resolve("gen-e-ro");
        generate(roEpm, roOut);
        String roController = Files.readString(
                roOut.resolve("src/main/java/com/acme/core/api/customerlite/CustomerLiteController.java"), StandardCharsets.UTF_8);
        assertThat(roController).doesNotContain("@PostMapping").doesNotContain("@PutMapping").doesNotContain("/disable");
        assertThat(roController).contains("@GetMapping");
    }

    // ---- F: enterprise integration controlled by contract ----

    @Test
    void enterpriseIntegrationControlledByContract() throws Exception {
        Map<String, Object> project = readYaml(repoRoot().resolve("tests/fixtures/v06-reference/generic/project.yaml"));
        Map<String, Map<String, Object>> manifests = readModuleManifests(
                repoRoot().resolve("tests/fixtures/v06-reference/generic/modules"));
        EffectiveProjectModel epm = resolve(project, manifests).effectiveProject();
        Path out = tempDir.resolve("gen-f");
        generate(epm, out);

        // permissions true -> @RequirePermission + permission seeds
        String controller = Files.readString(
                out.resolve("src/main/java/com/acme/core/api/customerlite/CustomerLiteController.java"), StandardCharsets.UTF_8);
        assertThat(controller).contains("@RequirePermission(\"customer_lite:item:create\")");
        String seed = Files.readString(
                out.resolve("src/test/resources/db/seed/seed-zzz-customer-lite.sql"), StandardCharsets.UTF_8);
        assertThat(seed).contains("customer_lite:item:read").contains("customer_lite:item:disable");
        // deterministic IDs (module sorted index 0 -> permission base 1000)
        assertThat(seed).contains("(1000, 'customer_lite:item:read'").contains("(1003, 'customer_lite:item:disable'");
        // dictionary true -> dictionary seed
        assertThat(seed).contains("customer_status").contains("'ENABLED'");
        // menu true -> menu seed
        assertThat(seed).contains("sys_menu").contains("'customer-lite'");
        // dataScope true -> departmentId + scope enforcement
        String entity = Files.readString(
                out.resolve("src/main/java/com/acme/core/domain/entity/CustomerLite.java"), StandardCharsets.UTF_8);
        assertThat(entity).contains("departmentId");
        String repository = Files.readString(
                out.resolve("src/main/java/com/acme/core/infrastructure/persistence/MybatisCustomerLiteRepository.java"), StandardCharsets.UTF_8);
        assertThat(repository).contains("DataScope").contains("department_id");
        // operationLog true -> @OperationLog
        assertThat(controller).contains("@OperationLog");

        // minimal contract without enterprise integrations -> no permission/dictionary/menu seeds
        Map<String, Object> plainProject = new LinkedHashMap<>();
        plainProject.put("schemaVersion", 1);
        plainProject.put("project", Map.of("id", "plain-demo", "name", "Plain Demo", "version", "1.0.0",
                "basePackage", "com.acme.core"));
        plainProject.put("platform", Map.of("id", "engineering-platform"));
        plainProject.put("quality", Map.of("minimum", "Q2"));
        plainProject.put("modules", List.of("customer-lite"));
        Map<String, Map<String, Object>> plainManifests = new LinkedHashMap<>(manifests);
        Map<String, Object> plainModule = new LinkedHashMap<>();
        plainModule.put("schemaVersion", 1);
        plainModule.put("module", Map.of("id", "customer-lite", "name", "CustomerLite", "version", "1.0.0", "type", "business"));
        Map<String, Object> plainBusiness = new LinkedHashMap<>();
        plainBusiness.put("table", "customer_lite");
        Map<String, Object> plainEntity = new LinkedHashMap<>();
        plainEntity.put("name", "CustomerLite");
        plainEntity.put("fields", List.of(
                Map.of("name", "code", "type", "string", "required", true, "unique", true, "length", 50),
                Map.of("name", "name", "type", "string", "required", true, "length", 100)));
        plainBusiness.put("entity", plainEntity);
        plainBusiness.put("features", List.of("list", "detail"));
        plainBusiness.put("enterprise", Map.of());
        plainModule.put("business", plainBusiness);
        plainManifests.put("customer-lite", plainModule);
        EffectiveProjectModel plainEpm = resolve(plainProject, plainManifests).effectiveProject();
        Path plainOut = tempDir.resolve("gen-f-plain");
        generate(plainEpm, plainOut);
        String plainSeed = Files.readString(
                plainOut.resolve("src/test/resources/db/seed/seed-zzz-customer-lite.sql"), StandardCharsets.UTF_8);
        assertThat(plainSeed).doesNotContain("sys_permission").doesNotContain("sys_menu").doesNotContain("sys_dictionary");
        String plainController = Files.readString(
                plainOut.resolve("src/main/java/com/acme/core/api/customerlite/CustomerLiteController.java"), StandardCharsets.UTF_8);
        assertThat(plainController).doesNotContain("@RequirePermission").doesNotContain("@OperationLog");
    }

    // ---- G/H: CustomerLite + WarehouseLite generate with NO dedicated asset ----

    @Test
    void customerLiteAndWarehouseLiteGenerateWithoutDedicatedAssets() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        assertThat(repo.capabilities()).doesNotContainKeys("customer-lite", "warehouse-lite",
                "customer-reference", "warehouse-reference", "frontend-customer-lite", "frontend-warehouse-lite");
        Map<String, Object> project = readYaml(repoRoot().resolve("tests/fixtures/v06-reference/generic/project.yaml"));
        Map<String, Map<String, Object>> manifests = readModuleManifests(
                repoRoot().resolve("tests/fixtures/v06-reference/generic/modules"));
        EffectiveProjectModel epm = resolve(project, manifests).effectiveProject();
        Path out = tempDir.resolve("gen-gh");
        AssetProjectGenerator.GenerationResult generated = generate(epm, out);
        assertThat(generated.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);
        assertThat(Files.exists(out.resolve("src/main/java/com/acme/core/domain/entity/CustomerLite.java"))).isTrue();
        assertThat(Files.exists(out.resolve("src/main/java/com/acme/core/domain/entity/WarehouseLite.java"))).isTrue();
        assertThat(Files.exists(out.resolve("frontend/src/views/customerlite/CustomerLiteListView.vue"))).isTrue();
        assertThat(Files.exists(out.resolve("frontend/src/views/warehouselite/WarehouseLiteListView.vue"))).isTrue();
    }

    // ---- I: Supplier migrates through Generic Generator (no supplier-reference) ----

    @Test
    void supplierGenericMigrationProof() throws Exception {
        Map<String, Object> project = readYaml(repoRoot().resolve("tests/fixtures/v06-reference/generic-supplier/project.yaml"));
        Map<String, Map<String, Object>> manifests = readModuleManifests(
                repoRoot().resolve("tests/fixtures/v06-reference/generic-supplier/modules"));
        ResolutionResult result = resolve(project, manifests);
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        EffectiveProjectModel epm = result.effectiveProject();
        assertThat(epm.businessModules()).extracting(ResolvedBusinessModule::id).contains("supplier");

        Path out = tempDir.resolve("gen-i");
        AssetProjectGenerator.GenerationResult generated = generate(epm, out);
        assertThat(generated.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);
        // generic generator produced the CRUD files (no supplier-reference capability enabled)
        for (String f : List.of(
                "src/main/java/com/acme/core/domain/entity/Supplier.java",
                "src/main/java/com/acme/core/application/supplier/SupplierService.java",
                "src/main/java/com/acme/core/api/supplier/SupplierController.java",
                "src/main/java/com/acme/core/infrastructure/persistence/MybatisSupplierRepository.java",
                "src/main/resources/db/migration/V100__supplier.sql",
                "frontend/src/types/supplier.ts",
                "frontend/src/api/supplier.ts",
                "frontend/src/router/business/supplier.ts",
                "frontend/src/views/supplier/SupplierListView.vue")) {
            assertThat(Files.exists(out.resolve(f))).as(f).isTrue();
        }
        // supplier generic output must not depend on supplier-reference templates
        String service = Files.readString(
                out.resolve("src/main/java/com/acme/core/application/supplier/SupplierService.java"), StandardCharsets.UTF_8);
        assertThat(service).contains("Generic Module Generator");
    }

    // ---- J: frontend-auth no longer hardcodes product/supplier glob ----

    @Test
    void frontendAuthHasNoHardcodedBusinessGlobs() throws Exception {
        Map<String, Object> project = readYaml(repoRoot().resolve("tests/fixtures/v06-reference/generic/project.yaml"));
        Map<String, Map<String, Object>> manifests = readModuleManifests(
                repoRoot().resolve("tests/fixtures/v06-reference/generic/modules"));
        EffectiveProjectModel epm = resolve(project, manifests).effectiveProject();
        Path out = tempDir.resolve("gen-j");
        generate(epm, out);

        String router = Files.readString(out.resolve("frontend/src/router/index.ts"), StandardCharsets.UTF_8);
        assertThat(router).contains("import.meta.glob('../router/business/*.ts', { eager: true })")
                .doesNotContain("product.ts").doesNotContain("supplier.ts").doesNotContain("customer");
    }

    // ---- K: new module needs no *-reference capability (implied by G/H; assert registry-free) ----

    @Test
    void newModuleNeedsNoReferenceCapability() throws Exception {
        // G/H already prove generation; here assert that the fixture declares ONLY modules
        Map<String, Object> project = readYaml(repoRoot().resolve("tests/fixtures/v06-reference/generic/project.yaml"));
        List<?> capabilities = (List<?>) project.get("capabilities");
        assertThat(capabilities).noneMatch(c -> String.valueOf(c).contains("customer")
                || String.valueOf(c).contains("warehouse"));
    }

    // ---- L: regeneration deterministic ----

    @Test
    void regenerationDeterministic() throws Exception {
        Map<String, Object> project = readYaml(repoRoot().resolve("tests/fixtures/v06-reference/generic/project.yaml"));
        Map<String, Map<String, Object>> manifests = readModuleManifests(
                repoRoot().resolve("tests/fixtures/v06-reference/generic/modules"));
        EffectiveProjectModel epm = resolve(project, manifests).effectiveProject();
        Path out1 = tempDir.resolve("det1");
        Path out2 = tempDir.resolve("det2");
        generate(epm, out1);
        generate(epm, out2);
        List<Path> files1;
        try (var stream = Files.walk(out1)) {
            files1 = stream.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains(".generator")).sorted().toList();
        }
        assertThat(files1).isNotEmpty();
        for (Path f1 : files1) {
            Path rel = out1.relativize(f1);
            assertThat(Files.exists(out2.resolve(rel))).as("same: " + rel).isTrue();
            assertThat(Files.readAllBytes(f1)).as("byte-identical: " + rel)
                    .isEqualTo(Files.readAllBytes(out2.resolve(rel)));
        }
    }

    // ---- M: Product/Supplier Reference regression ----
    // Covered by running ProductReferenceWork005Test + V06Work002SupplierTest in the same suite;
    // here assert the generic fixture does not disturb reference assets.

    @Test
    void referenceAssetsUnaffectedByGenericGenerator() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        assertThat(repo.capabilities()).containsKeys("product-reference", "supplier-reference",
                "frontend-product-reference", "frontend-supplier-reference");
        // generic-demo project has no reference capabilities enabled -> no reference files generated
        Map<String, Object> project = readYaml(repoRoot().resolve("tests/fixtures/v06-reference/generic/project.yaml"));
        Map<String, Map<String, Object>> manifests = readModuleManifests(
                repoRoot().resolve("tests/fixtures/v06-reference/generic/modules"));
        EffectiveProjectModel epm = resolve(project, manifests).effectiveProject();
        Path out = tempDir.resolve("gen-m");
        generate(epm, out);
        assertThat(Files.exists(out.resolve("src/main/java/com/acme/core/domain/entity/Product.java"))).isFalse();
    }

    // ---- generated project acceptance: backend targeted tests + frontend test/build ----

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void generatedProjectAcceptance() throws Exception {
        Map<String, Object> project = readYaml(repoRoot().resolve("tests/fixtures/v06-reference/generic/project.yaml"));
        Map<String, Map<String, Object>> manifests = readModuleManifests(
                repoRoot().resolve("tests/fixtures/v06-reference/generic/modules"));
        EffectiveProjectModel epm = resolve(project, manifests).effectiveProject();
        Path out = tempDir.resolve("real");
        AssetProjectGenerator.GenerationResult generated = generate(epm, out);
        assertThat(generated.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);
        ConformanceResult conformance = new ConformanceValidator(AssetRepository.load(repoRoot())).validate(epm, out);
        assertThat(conformance.status()).as("conformance must PASS:\n%s", conformance.summary())
                .isEqualTo(ConformanceResult.Status.PASS);

        // backend: generic module targeted tests + dictionary E2E
        Path settings = Path.of("/tmp/m2-settings-proxy.xml");
        List<String> cmd = new ArrayList<>(List.of("mvn", "-B", "-f",
                out.resolve("pom.xml").toString(), "test",
                "-Dtest=CustomerLiteModelUnitTest,WarehouseLiteModelUnitTest,DictionaryE2ETest",
                "-Dsurefire.failIfNoSpecifiedTests=false"));
        if (Files.exists(settings)) {
            cmd.add(2, "-s");
            cmd.add(3, settings.toString());
        }
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(900, TimeUnit.SECONDS);
        assertThat(finished).as("mvn test must finish").isTrue();
        assertThat(process.exitValue())
                .as("generated backend generic tests must pass:\n%s", output)
                .isEqualTo(0);
        for (String suite : List.of("com.acme.core.CustomerLiteModelUnitTest",
                "com.acme.core.WarehouseLiteModelUnitTest")) {
            Path report = out.resolve("target/surefire-reports/" + suite + ".txt");
            assertThat(Files.exists(report)).as(suite + " report exists").isTrue();
            String text = Files.readString(report, StandardCharsets.UTF_8);
            assertThat(text).as(suite + " ran").contains("Tests run:");
            assertThat(text).as(suite + " no failures").contains("Failures: 0");
        }

        // frontend: full test + build
        Path frontend = out.resolve("frontend");
        assertThat(run(frontend, "pnpm", "install")).as("pnpm install").isEqualTo(0);
        assertThat(run(frontend, "pnpm", "test")).as("pnpm test").isEqualTo(0);
        assertThat(run(frontend, "pnpm", "build")).as("pnpm build").isEqualTo(0);
    }

    private static int run(Path dir, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(dir.toFile())
                .redirectErrorStream(true).start();
        byte[] out = process.getInputStream().readAllBytes();
        boolean finished = process.waitFor(600, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("timeout: " + String.join(" ", command));
        }
        return process.exitValue();
    }
}
