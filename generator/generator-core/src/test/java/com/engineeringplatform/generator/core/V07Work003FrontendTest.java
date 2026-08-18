package com.engineeringplatform.generator.core;

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
 * V07-WORK-003 — Relationship-aware Generic Frontend Generation.
 *
 * Proves the frontend renderer turns Contract V2 semantics into reusable UI:
 *   - ReferenceSelect for reference fields (supplier/product)
 *   - DatePicker for date fields
 *   - StatusSelect for enum fields
 *   - MoneyInput / MoneyText for money fields
 *   - EditableDetailTable for master/child items (Add Row / Remove Row)
 * No PurchaseOrder-specific page or component exists — everything is driven
 * by the resolved Contract. Generated frontend must pass pnpm test + build.
 */
class V07Work003FrontendTest {

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
                                            Map<String, Map<String, Object>> moduleManifests,
                                            Set<String> extraModules) throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        List<String> explicitCapabilities = ReferenceResolver.extractIds(project.get("capabilities"));
        AssetContext assetContext = AssetResolution.resolve(repo, explicitCapabilities, realPlatform());
        Map<String, Map<String, Object>> providerManifests = repo.toProviderManifests();
        Set<String> known = new java.util.LinkedHashSet<>(Set.of("sample-customer", "supplier", "customer-lite",
                "warehouse-lite", "product", "purchase-order", "purchase-order-item"));
        known.addAll(extraModules);
        Map<String, Set<String>> registry = new LinkedHashMap<>();
        registry.put("modules", known);
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

    private static EffectiveProjectModel v07Epm() throws Exception {
        Map<String, Object> project = readYaml(repoRoot().resolve("tests/fixtures/v07-reference/generic/project.yaml"));
        Map<String, Map<String, Object>> manifests = readModuleManifests(
                repoRoot().resolve("tests/fixtures/v07-reference/generic/modules"));
        ResolutionResult result = resolve(project, manifests, Set.of());
        assertThat(result.status()).isEqualTo(ResolutionResult.Status.SUCCESS);
        return result.effectiveProject();
    }

    private static AssetProjectGenerator.Options options() {
        return new AssetProjectGenerator.Options(
                "com.acme.core", "v07-rel-demo", "com.acme", "v07-rel-demo", "1.0.0", Map.of());
    }

    private static AssetProjectGenerator.GenerationResult generate(EffectiveProjectModel epm, Path out)
            throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        Files.createDirectories(out);
        return new AssetProjectGenerator().generate(epm, repo, options(), out);
    }

    private static String read(Path out, String rel) throws Exception {
        return Files.readString(out.resolve(rel), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    // ---- A: reusable relationship-aware components exist in the asset ----

    @Test
    void relationshipComponentsExist() throws Exception {
        AssetRepository repo = AssetRepository.load(repoRoot());
        assertThat(repo.capabilities().keySet()).contains("frontend-enterprise-management");
        for (String comp : List.of("ReferenceSelect.vue", "EditableDetailTable.vue",
                "MoneyText.vue", "MoneyInput.vue", "StatusSelect.vue")) {
            Path tpl = repoRoot().resolve("capabilities/frontend-enterprise-management/templates/src/components/" + comp + ".ftl");
            assertThat(Files.exists(tpl)).as(comp + " template exists").isTrue();
        }
    }

    // ---- B: master module renders relationship-aware edit controls ----

    @Test
    void masterEditViewRelationshipControls() throws Exception {
        EffectiveProjectModel epm = v07Epm();
        Path out = tempDir.resolve("gen-b");
        AssetProjectGenerator.GenerationResult generated = generate(epm, out);
        assertThat(generated.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);

        String edit = read(out, "frontend/src/views/purchaseorder/PurchaseOrderEditView.vue");
        // reference -> ReferenceSelect with the target module api
        assertThat(edit).contains("ReferenceSelect")
                .contains("supplierApi")
                .contains(":api=\"supplierApi\"")
                .contains("label-field=\"name\"");
        // date -> DatePicker
        assertThat(edit).contains("el-date-picker")
                .contains("value-format=\"YYYY-MM-DD\"");
        // enum -> StatusSelect with Contract values
        assertThat(edit).contains("StatusSelect")
                .contains("value: 'DRAFT'")
                .contains("value: 'CONFIRMED'")
                .contains("value: 'CLOSED'");
        // money -> MoneyInput
        assertThat(edit).contains("MoneyInput")
                .contains(":precision=\"2\"");
        // master detail items -> EditableDetailTable with Add/Remove
        assertThat(edit).contains("EditableDetailTable")
                .contains("form.items");
        // the reusable component itself provides Add Row / Remove Row
        String table = read(out, "frontend/src/components/EditableDetailTable.vue");
        assertThat(table).contains("detail-add-row").contains("detail-remove-row");
        // child reference inside the items table -> productApi
        assertThat(edit).contains("productApi")
                .contains("import { productApi } from '../../api/product'");
    }

    // ---- B2 (V07-WORK-006 blocker fix): master ListView Create/Edit drawer
    // renders EditableDetailTable and submits items from the form ----------------

    @Test
    void masterListViewCreateFormItems() throws Exception {
        EffectiveProjectModel epm = v07Epm();
        Path out = tempDir.resolve("gen-b2");
        AssetProjectGenerator.GenerationResult generated = generate(epm, out);
        assertThat(generated.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);

        String list = read(out, "frontend/src/views/purchaseorder/PurchaseOrderListView.vue");
        // Create/Edit drawer renders the generic master/detail editable table
        assertThat(list).contains("EditableDetailTable")
                .contains("v-model=\"form.items\"");
        // add/remove row affordances live inside the reusable component (asserted elsewhere)
        String table = read(out, "frontend/src/components/EditableDetailTable.vue");
        assertThat(table).contains("detail-add-row").contains("detail-remove-row");
        // submit sends items from the form (not a hard-coded empty array)
        assertThat(list).contains("items: form.items")
                .contains("const body = {")
                .contains("await purchaseOrderApi.create(body)");
        // openCreate initialises an empty items array (correct: new master has no children yet)
        assertThat(list).contains("items: [] as PurchaseOrderItemItemInput[]");
        // child columns driven by field semantics: reference -> productApi, money, number
        assertThat(list).contains("kind: 'reference', api: productApi")
                .contains("kind: 'money'")
                .contains("kind: 'number'");
        // master reference field renders ReferenceSelect
        assertThat(list).contains("ReferenceSelect")
                .contains(":api=\"supplierApi\"");
        // child reference api imported
        assertThat(list).contains("import { productApi } from '../../api/product'");
    }

    // ---- B3 (V07-WORK-006 blocker fix): EditableDetailTable imports the
    // field-semantic renderers its template actually uses ------------------------

    @Test
    void editableDetailTableImportsRenderers() throws Exception {
        EffectiveProjectModel epm = v07Epm();
        Path out = tempDir.resolve("gen-b3");
        generate(epm, out);

        String table = read(out, "frontend/src/components/EditableDetailTable.vue");
        assertThat(table).contains("import ReferenceSelect from './ReferenceSelect.vue'")
                .contains("import MoneyInput from './MoneyInput.vue'");
        // DetailColumn api contract is the canonical PageResult shape (items, not records)
        assertThat(table).contains("Promise<{ items?:")
                .doesNotContain("records?");
    }

    // ---- B4 (V07-WORK-006 blocker fix): ReferenceSelect consumes the canonical
    // PageResult shape ({ items, total, page, size }) -----------------------------

    @Test
    void referenceSelectReadsCanonicalPageResult() throws Exception {
        EffectiveProjectModel epm = v07Epm();
        Path out = tempDir.resolve("gen-b4");
        generate(epm, out);

        String ref = read(out, "frontend/src/components/ReferenceSelect.vue");
        assertThat(ref).contains("options.value = data.items ?? []")
                .doesNotContain("data.records");
    }

    // ---- C: master detail view renders items table + MoneyText ----

    @Test
    void masterDetailViewItemsAndMoney() throws Exception {
        EffectiveProjectModel epm = v07Epm();
        Path out = tempDir.resolve("gen-c");
        generate(epm, out);

        String detail = read(out, "frontend/src/views/purchaseorder/PurchaseOrderDetailView.vue");
        assertThat(detail).contains("MoneyText")
                .contains("row.totalAmount")
                .contains("row.items")
                .contains("detail-items");
        String types = read(out, "frontend/src/types/purchase-order.ts");
        assertThat(types).contains("items?: PurchaseOrderItem[]")
                .contains("items: PurchaseOrderItemItemInput[]")
                .contains("interface PurchaseOrderItemItemInput")
                .contains("productId")
                .contains("unitPrice")
                .contains("amount");
    }

    // ---- D: plain V0.6 module stays on simple controls (no relation leakage) ----

    @Test
    void v06ModuleNoRelationLeakage() throws Exception {
        Map<String, Object> project = readYaml(repoRoot().resolve("tests/fixtures/v06-reference/generic/project.yaml"));
        Map<String, Map<String, Object>> manifests = readModuleManifests(
                repoRoot().resolve("tests/fixtures/v06-reference/generic/modules"));
        EffectiveProjectModel epm = resolve(project, manifests, Set.of()).effectiveProject();
        Path out = tempDir.resolve("gen-d");
        generate(epm, out);

        String edit = read(out, "frontend/src/views/customerlite/CustomerLiteEditView.vue");
        assertThat(edit).doesNotContain("ReferenceSelect")
                .doesNotContain("EditableDetailTable")
                .doesNotContain("MoneyInput")
                .doesNotContain("StatusSelect");
        String types = read(out, "frontend/src/types/customer-lite.ts");
        assertThat(types).doesNotContain("items");
    }

    // ---- E: generated project frontend test + build (relationship views compile) ----

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void generatedProjectFrontendTestAndBuild() throws Exception {
        EffectiveProjectModel epm = v07Epm();
        Path out = tempDir.resolve("gen-e");
        AssetProjectGenerator.GenerationResult generated = generate(epm, out);
        assertThat(generated.execution().status()).isEqualTo(ExecutionResult.ExecutionStatus.SUCCESS);

        Path frontend = out.resolve("frontend");
        assertThat(Files.exists(frontend.resolve("src/components/ReferenceSelect.vue"))).isTrue();
        assertThat(Files.exists(frontend.resolve("src/components/EditableDetailTable.vue"))).isTrue();
        assertThat(Files.exists(frontend.resolve("src/components/MoneyText.vue"))).isTrue();
        assertThat(Files.exists(frontend.resolve("src/components/MoneyInput.vue"))).isTrue();
        assertThat(Files.exists(frontend.resolve("src/components/StatusSelect.vue"))).isTrue();

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
        if (process.exitValue() != 0) {
            System.out.println(new String(out, StandardCharsets.UTF_8).substring(
                    Math.max(0, out.length - 4000)));
        }
        return process.exitValue();
    }
}
