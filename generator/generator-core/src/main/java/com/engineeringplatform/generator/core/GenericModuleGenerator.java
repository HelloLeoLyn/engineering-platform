package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.BusinessEntityField;
import com.engineeringplatform.generator.contracts.EffectiveProjectModel;
import com.engineeringplatform.generator.contracts.ResolvedBusinessModule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V06-WORK-002B — Contract-driven Generic Module Generator.
 *
 * Generates a full-stack business module (backend + frontend) purely from the
 * resolved Business Module Contract (EPM.businessModules[]). No per-entity
 * *-reference capability is required: the module id/name/table/entity/fields/
 * features/enterprise/frontend metadata drive every generated artifact.
 *
 * Architecture rule: this generator only PRODUCES file content (target +
 * content). It runs inside the existing AssetProjectGenerator file-set stage,
 * so Ownership / PathSafety / GenerationPlanner / GeneratorExecutor /
 * Conformance all stay active — no second execution engine.
 *
 * Deterministic ID allocation: business modules are sorted by id; each module
 * owns a contiguous ID segment (permission / role-permission / dictionary /
 * menu / migration), so identical contracts regenerate identical outputs and
 * multiple modules never collide.
 */
public final class GenericModuleGenerator {

    /** A generated file (target relative to generated project root). */
    public record GeneratedFile(String target, String content) {
    }

    public static final int MIGRATION_BASE = 100;      // V100+ (existing V001-V009 untouched)
    public static final int PERMISSION_BASE = 1000;    // 4 perms per module, step 8
    public static final int ROLE_PERM_BASE = 2000;     // step 8
    public static final int DICT_TYPE_BASE = 3000;     // step 8
    public static final int DICT_ITEM_BASE = 4000;     // step 32
    public static final int MENU_BASE = 5000;          // step 8

    /**
     * Generate all business module files for the EPM.
     * Modules are sorted by id for determinism.
     */
    public List<GeneratedFile> generate(EffectiveProjectModel epm, Map<String, String> vars) {
        List<ResolvedBusinessModule> modules = new ArrayList<>(epm.businessModules());
        modules.sort(Comparator.comparing(ResolvedBusinessModule::id));
        List<GeneratedFile> files = new ArrayList<>();
        for (int i = 0; i < modules.size(); i++) {
            ResolvedBusinessModule module = modules.get(i);
            Ids ids = new Ids(i);
            files.addAll(backendFiles(module, vars, ids));
            // V06-WORK-003: frontend rendering split into GenericFrontendTemplates
            files.addAll(new GenericFrontendTemplates().generateFrontend(module));
        }
        return files;
    }

    /** Deterministic per-module ID segment. */
    private record Ids(int idx) {
        long permission(int offset) { return PERMISSION_BASE + idx * 8L + offset; }        // 0..3
        long rolePermission(int offset) { return ROLE_PERM_BASE + idx * 8L + offset; }     // 0..3
        long dictType(int offset) { return DICT_TYPE_BASE + idx * 8L + offset; }           // 0..1
        long dictItem(int offset) { return DICT_ITEM_BASE + idx * 32L + offset; }
        long menu(int offset) { return MENU_BASE + idx * 8L + offset; }                    // 0..2
        int migration() { return MIGRATION_BASE + idx; }
    }

    // ------------------------------------------------------------------
    // Backend
    // ------------------------------------------------------------------

    private List<GeneratedFile> backendFiles(ResolvedBusinessModule module, Map<String, String> vars,
                                             Ids ids) {
        String pkg = vars.get("package");
        String pkgPath = vars.get("basePackagePath");
        String entity = module.entity().name();
        String entityVar = decapitalize(entity);
        String modPkg = module.id().replace("-", "");
        String table = module.table() == null ? module.id().replace("-", "_") : module.table();
        List<BusinessEntityField> fields = module.entity().fields();
        boolean dataScope = bool(module.enterprise(), "dataScope");
        boolean permissions = bool(module.enterprise(), "permissions");
        boolean dictionary = bool(module.enterprise(), "dictionary");
        boolean operationLog = bool(module.enterprise(), "operationLog");
        boolean menu = bool(module.enterprise(), "menu");
        List<String> features = module.features();
        String route = frontendRoute(module);

        List<GeneratedFile> files = new ArrayList<>();

        // ---- Entity ----
        files.add(new GeneratedFile(
                "src/main/java/" + pkgPath + "/domain/entity/" + entity + ".java",
                entitySource(pkg, entity, fields, dataScope)));

        // ---- application DTOs ----
        files.add(new GeneratedFile(
                "src/main/java/" + pkgPath + "/application/" + modPkg + "/" + entity + "CreateRequest.java",
                createRequestSource(pkg, modPkg, entity, fields)));
        files.add(new GeneratedFile(
                "src/main/java/" + pkgPath + "/application/" + modPkg + "/" + entity + "UpdateRequest.java",
                updateRequestSource(pkg, modPkg, entity, fields)));
        files.add(new GeneratedFile(
                "src/main/java/" + pkgPath + "/application/" + modPkg + "/" + entity + "Response.java",
                responseSource(pkg, modPkg, entity, fields, dataScope)));

        // ---- Port ----
        files.add(new GeneratedFile(
                "src/main/java/" + pkgPath + "/application/" + modPkg + "/" + entity + "Port.java",
                portSource(pkg, modPkg, entity, features, fields)));

        // ---- Service ----
        files.add(new GeneratedFile(
                "src/main/java/" + pkgPath + "/application/" + modPkg + "/" + entity + "Service.java",
                serviceSource(pkg, modPkg, entity, entityVar, fields, features, dataScope, dictionary, ids, table)));

        // ---- persistence ----
        files.add(new GeneratedFile(
                "src/main/java/" + pkgPath + "/infrastructure/persistence/mapper/" + entity + "Mapper.java",
                mapperSource(pkg, entity)));
        files.add(new GeneratedFile(
                "src/main/java/" + pkgPath + "/infrastructure/persistence/Mybatis" + entity + "Repository.java",
                repositorySource(pkg, modPkg, entity, entityVar, table, fields, dataScope)));
        files.add(new GeneratedFile(
                "src/main/java/" + pkgPath + "/infrastructure/persistence/" + entity + "BeansConfig.java",
                beansConfigSource(pkg, modPkg, entity, dataScope, dictionary)));

        // ---- Controller ----
        files.add(new GeneratedFile(
                "src/main/java/" + pkgPath + "/api/" + modPkg + "/" + entity + "Controller.java",
                controllerSource(pkg, modPkg, entity, entityVar, features, dataScope, permissions, operationLog, table, fields)));

        // ---- migration ----
        files.add(new GeneratedFile(
                "src/main/resources/db/migration/V" + ids.migration() + "__" + table + ".sql",
                migrationSource(table, fields, dataScope)));

        // ---- test seed (deterministic IDs) ----
        String seedContent = seedSource(module, ids, fields, dataScope, permissions, dictionary, menu, route, table);
        files.add(new GeneratedFile(
                "src/test/resources/db/seed/seed-zzz-" + module.id() + ".sql",
                seedContent));
        // V06-WORK-003: e2e profile seed copy (same as reference assets: e2e runtime
        // loads db/seed-e2e so generated modules have deterministic demo data too)
        files.add(new GeneratedFile(
                "src/main/resources/db/seed-e2e/seed-zzz-" + module.id() + ".sql",
                seedContent));

        // ---- model unit test (generated project targeted test) ----
        files.add(new GeneratedFile(
                "src/test/java/" + pkgPath + "/" + entity + "ModelUnitTest.java",
                modelUnitTestSource(pkg, modPkg, entity, entityVar, fields, features, dataScope, dictionary, table)));

        return files;
    }

    private String entitySource(String pkg, String entity, List<BusinessEntityField> fields, boolean dataScope) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".domain.entity;\n\n");
        sb.append("import java.time.LocalDateTime;\n");
        if (hasType(fields, "date")) sb.append("import java.time.LocalDate;\n");
        if (hasType(fields, "number") || hasType(fields, "decimal")) sb.append("import java.math.BigDecimal;\n");
        sb.append("\n/**\n * ").append(entity).append(" entity (generated by engineering-platform Generic Module Generator).\n")
                .append(" * Pure domain POJO — no persistence annotations (infrastructure owns mapping).\n */\n");
        sb.append("public class ").append(entity).append(" {\n\n");
        sb.append("    private Long id;\n");
        for (BusinessEntityField f : fields) {
            if (isSystemField(f)) continue;
            sb.append("    private ").append(javaType(f)).append(" ").append(f.name()).append(";\n");
        }
        if (dataScope) sb.append("    private Long departmentId;\n");
        sb.append("    private Long createdBy;\n");
        sb.append("    private LocalDateTime createdAt;\n");
        sb.append("    private LocalDateTime updatedAt;\n\n");
        sb.append("    public ").append(entity).append("() {\n    }\n\n");
        // getters/setters
        appendAccessors(sb, "Long", "id");
        for (BusinessEntityField f : fields) {
            if (isSystemField(f)) continue;
            appendAccessors(sb, javaType(f), f.name());
        }
        if (dataScope) appendAccessors(sb, "Long", "departmentId");
        appendAccessors(sb, "Long", "createdBy");
        appendAccessors(sb, "LocalDateTime", "createdAt");
        appendAccessors(sb, "LocalDateTime", "updatedAt");
        sb.append("}\n");
        return sb.toString();
    }

    private void appendAccessors(StringBuilder sb, String type, String name) {
        String cap = capitalize(name);
        sb.append("    public ").append(type).append(" get").append(cap).append("() {\n")
                .append("        return ").append(name).append(";\n    }\n\n");
        sb.append("    public void set").append(cap).append("(").append(type).append(" ").append(name)
                .append(") {\n        this.").append(name).append(" = ").append(name).append(";\n    }\n\n");
    }

    private String createRequestSource(String pkg, String modPkg, String entity, List<BusinessEntityField> fields) {
        StringBuilder params = new StringBuilder();
        StringBuilder docs = new StringBuilder();
        boolean first = true;
        for (BusinessEntityField f : fields) {
            if (isSystemField(f)) continue;
            if (!first) params.append(", ");
            first = false;
            params.append(javaType(f)).append(" ").append(f.name());
            if (f.required()) {
                docs.append(" * @param ").append(f.name()).append(" required\n");
            }
        }
        return "package " + pkg + ".application." + modPkg + ";\n\n"
                + "/**\n * " + entity + " create request (generated by engineering-platform Generic Module Generator).\n"
                + " * Client never supplies createdBy/departmentId — derived from authenticated RequestContext.\n */\n"
                + "public record " + entity + "CreateRequest(" + params + ") {\n}\n";
    }

    private String updateRequestSource(String pkg, String modPkg, String entity, List<BusinessEntityField> fields) {
        StringBuilder params = new StringBuilder();
        boolean first = true;
        for (BusinessEntityField f : fields) {
            if (isSystemField(f)) continue;
            if (!first) params.append(", ");
            first = false;
            params.append(javaType(f)).append(" ").append(f.name());
        }
        return "package " + pkg + ".application." + modPkg + ";\n\n"
                + "/**\n * " + entity + " update request (generated by engineering-platform Generic Module Generator).\n"
                + " * createdBy/createdAt/departmentId are immutable — never accepted from client.\n */\n"
                + "public record " + entity + "UpdateRequest(" + params + ") {\n}\n";
    }

    private String responseSource(String pkg, String modPkg, String entity, List<BusinessEntityField> fields,
                                  boolean dataScope) {
        StringBuilder params = new StringBuilder();
        params.append("@com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)\n        Long id");
        for (BusinessEntityField f : fields) {
            if (isSystemField(f)) continue;
            params.append(",\n        ").append(javaType(f)).append(" ").append(f.name());
        }
        if (dataScope) params.append(",\n        Long departmentId");
        params.append(",\n        Long createdBy");
        params.append(",\n        java.time.LocalDateTime createdAt");
        params.append(",\n        java.time.LocalDateTime updatedAt");
        return "package " + pkg + ".application." + modPkg + ";\n\n"
                + "/**\n * " + entity + " response DTO (generated by engineering-platform Generic Module Generator).\n"
                + " * Stable API payload — never exposes the persistence entity or MyBatis types.\n */\n"
                + "public record " + entity + "Response(\n        " + params + ") {\n\n"
                + "    public static " + entity + "Response of("
                + responseParams(fields, dataScope) + ") {\n"
                + "        return new " + entity + "Response(" + responseArgs(fields, dataScope) + ");\n    }\n}\n";
    }

    private String responseParams(List<BusinessEntityField> fields, boolean dataScope) {
        StringBuilder sb = new StringBuilder("Long id");
        for (BusinessEntityField f : fields) {
            if (isSystemField(f)) continue;
            sb.append(", ").append(javaType(f)).append(" ").append(f.name());
        }
        if (dataScope) sb.append(", Long departmentId");
        sb.append(", Long createdBy, java.time.LocalDateTime createdAt, java.time.LocalDateTime updatedAt");
        return sb.toString();
    }

    private String responseArgs(List<BusinessEntityField> fields, boolean dataScope) {
        StringBuilder sb = new StringBuilder("id");
        for (BusinessEntityField f : fields) {
            if (isSystemField(f)) continue;
            sb.append(", ").append(f.name());
        }
        if (dataScope) sb.append(", departmentId");
        sb.append(", createdBy, createdAt, updatedAt");
        return sb.toString();
    }

    private String portSource(String pkg, String modPkg, String entity, List<String> features, List<BusinessEntityField> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".application.").append(modPkg).append(";\n\n");
        sb.append("import ").append(pkg).append(".application.datascope.DataPermissionContext;\n");
        sb.append("import ").append(pkg).append(".common.core.PageQuery;\n");
        sb.append("import ").append(pkg).append(".common.core.PageResult;\n");
        sb.append("import ").append(pkg).append(".domain.entity.").append(entity).append(";\n\n");
        sb.append("import java.util.List;\nimport java.util.Optional;\n\n");
        sb.append("/**\n * ").append(entity).append(" persistence contract (generated by engineering-platform Generic Module Generator).\n")
                .append(" * Application/business code depends only on this interface — never on MyBatis types.\n */\n");
        sb.append("public interface ").append(entity).append("Port {\n\n");
        sb.append("    Optional<").append(entity).append("> findById(Long id);\n\n");
        sb.append("    Optional<").append(entity).append("> findByIdInScope(Long id, DataPermissionContext context);\n\n");
        if (features.contains("list") || features.contains("detail")) {
            sb.append("    List<").append(entity).append("> findByScope(DataPermissionContext context);\n\n");
        }
        if (features.contains("list")) {
            sb.append("    PageResult<").append(entity).append("> findPageByScope(DataPermissionContext context, PageQuery query);\n\n");
        }
        if (features.contains("search")) {
            sb.append("    PageResult<").append(entity).append("> findPageByScopeFiltered(DataPermissionContext context, PageQuery query, String keyword, String status");
            for (BusinessEntityField f : fields) {
                if (isSystemField(f)) continue;
                if (Boolean.TRUE.equals(f.frontend().get("searchable"))) {
                    sb.append(", String ").append(f.name());
                }
            }
            sb.append(");\n\n");
        }
        // unique field existence (first unique non-system field, else id)
        String unique = firstUniqueField(features);
        if (features.contains("create") || features.contains("edit")) {
            sb.append("    boolean existsByUnique(String value, Long excludeId);\n\n");
        }
        sb.append("    ").append(entity).append(" insert(").append(entity).append(" entity);\n\n");
        sb.append("    int update(").append(entity).append(" entity);\n");
        sb.append("}\n");
        return sb.toString();
    }

    private String serviceSource(String pkg, String modPkg, String entity, String entityVar,
                                 List<BusinessEntityField> fields, List<String> features,
                                 boolean dataScope, boolean dictionary, Ids ids, String table) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".application.").append(modPkg).append(";\n\n");
        sb.append("import ").append(pkg).append(".application.datascope.DataPermissionContext;\n");
        if (dictionary) {
            sb.append("import ").append(pkg).append(".application.dictionary.DictionaryPort;\n");
        }
        sb.append("import ").append(pkg).append(".common.core.ErrorCode;\n");
        sb.append("import ").append(pkg).append(".common.core.PageQuery;\n");
        sb.append("import ").append(pkg).append(".common.core.PageResult;\n");
        sb.append("import ").append(pkg).append(".common.core.PlatformException;\n");
        if (dictionary) sb.append("import ").append(pkg).append(".domain.entity.DictionaryItem;\n");
        sb.append("import ").append(pkg).append(".domain.entity.").append(entity).append(";\n\n");
        sb.append("import java.time.LocalDateTime;\nimport java.util.List;\n\n");
        sb.append("/**\n * ").append(entity).append(" application service (generated by engineering-platform Generic Module Generator).\n")
                .append(" * Client never supplies createdBy/departmentId: they come from the authenticated RequestContext.\n */\n");
        sb.append("public class ").append(entity).append("Service {\n\n");
        sb.append("    private final ").append(entity).append("Port ").append(entityVar).append("Port;\n");
        if (dictionary) sb.append("    private final DictionaryPort dictionaryPort;\n");
        sb.append("\n    public ").append(entity).append("Service(")
                .append(entity).append("Port ").append(entityVar).append("Port");
        if (dictionary) sb.append(", DictionaryPort dictionaryPort");
        sb.append(") {\n        this.").append(entityVar).append("Port = ").append(entityVar).append("Port;\n");
        if (dictionary) sb.append("        this.dictionaryPort = dictionaryPort;\n");
        sb.append("    }\n\n");

        // create
        if (features.contains("create")) {
            sb.append("    public ").append(entity).append("Response create(").append(entity).append("CreateRequest request, Long userId, Long departmentId) {\n");
            for (BusinessEntityField f : fields) {
                if (isSystemField(f)) continue;
                if (f.required()) {
                    sb.append("        if (request.").append(f.name()).append("() == null")
                            .append(stringRequired(f) ? " || request." + f.name() + "().isBlank()" : "")
                            .append(") {\n");
                    sb.append("            throw new PlatformException(ErrorCode.of(\"").append(upper(entity)).append("_").append(upper(f.name())).append("_REQUIRED\", \"")
                            .append(f.name()).append(" is required\"));\n        }\n");
                }
                if (f.unique()) {
                    sb.append("        if (").append(entityVar).append("Port.existsByUnique(String.valueOf(request.").append(f.name()).append("()), null)) {\n");
                    sb.append("            throw new PlatformException(ErrorCode.of(\"").append(upper(entity)).append("_").append(upper(f.name())).append("_DUPLICATE\", \"")
                            .append(f.name()).append(" already exists\"));\n        }\n");
                }
                if (dictionary && "dictionary".equals(f.semantic()) && f.dictionary() != null) {
                    sb.append("        validateDictionary(\"").append(f.dictionary()).append("\", request.").append(f.name()).append("());\n");
                }
            }
            sb.append("        ").append(entity).append(" ").append(entityVar).append(" = new ").append(entity).append("();\n");
            for (BusinessEntityField f : fields) {
                if (isSystemField(f)) continue;
                sb.append("        ").append(entityVar).append(".set").append(capitalize(f.name())).append("(request.").append(f.name()).append("());\n");
            }
            if (dataScope) sb.append("        ").append(entityVar).append(".setDepartmentId(departmentId);\n");
            sb.append("        ").append(entityVar).append(".setCreatedBy(userId);\n");
            sb.append("        LocalDateTime now = LocalDateTime.now();\n");
            sb.append("        ").append(entityVar).append(".setCreatedAt(now);\n");
            sb.append("        ").append(entityVar).append(".setUpdatedAt(now);\n");
            sb.append("        ").append(entityVar).append("Port.insert(").append(entityVar).append(");\n");
            sb.append("        return toResponse(").append(entityVar).append(");\n    }\n\n");
        }

        // detail
        if (features.contains("detail")) {
            sb.append("    public ").append(entity).append("Response get(Long id, DataPermissionContext context) {\n");
            sb.append("        ").append(entity).append(" ").append(entityVar).append(" = ").append(entityVar)
                    .append("Port.findByIdInScope(id, context)\n");
            sb.append("                .orElseThrow(() -> new PlatformException(ErrorCode.of(\"").append(upper(entity)).append("_NOT_FOUND\", \"")
                    .append(entityVar).append(" not found: \" + id)));\n");
            sb.append("        return toResponse(").append(entityVar).append(");\n    }\n\n");
        }

        // list
        if (features.contains("list")) {
            sb.append("    public List<").append(entity).append("Response> list(DataPermissionContext context) {\n");
            sb.append("        return ").append(entityVar).append("Port.findByScope(context).stream().map(this::toResponse).toList();\n    }\n\n");
            sb.append("    public PageResult<").append(entity).append("Response> page(PageQuery query, DataPermissionContext context) {\n");
            sb.append("        PageResult<").append(entity).append("> result = ").append(entityVar).append("Port.findPageByScope(context, query);\n");
            sb.append("        List<").append(entity).append("Response> items = result.items().stream().map(this::toResponse).toList();\n");
            sb.append("        return PageResult.of(items, result.total(), result.page(), result.size());\n    }\n\n");
        }

        // search
        if (features.contains("search")) {
            sb.append("    public PageResult<").append(entity).append("Response> pageFiltered(PageQuery query, DataPermissionContext context, String keyword, String status");
            for (BusinessEntityField f : fields) {
                if (isSystemField(f)) continue;
                if (Boolean.TRUE.equals(f.frontend().get("searchable"))) {
                    sb.append(", String ").append(f.name());
                }
            }
            sb.append(") {\n");
            sb.append("        PageResult<").append(entity).append("> result = ").append(entityVar)
                    .append("Port.findPageByScopeFiltered(context, query, keyword, status");
            for (BusinessEntityField f : fields) {
                if (isSystemField(f)) continue;
                if (Boolean.TRUE.equals(f.frontend().get("searchable"))) {
                    sb.append(", ").append(f.name());
                }
            }
            sb.append(");\n");
            sb.append("        List<").append(entity).append("Response> items = result.items().stream().map(this::toResponse).toList();\n");
            sb.append("        return PageResult.of(items, result.total(), result.page(), result.size());\n    }\n\n");
        }

        // update
        if (features.contains("edit")) {
            sb.append("    public ").append(entity).append("Response update(Long id, ").append(entity).append("UpdateRequest request, DataPermissionContext context) {\n");
            sb.append("        ").append(entity).append(" ").append(entityVar).append(" = ").append(entityVar)
                    .append("Port.findByIdInScope(id, context)\n");
            sb.append("                .orElseThrow(() -> new PlatformException(ErrorCode.of(\"").append(upper(entity)).append("_NOT_FOUND\", \"")
                    .append(entityVar).append(" not found: \" + id)));\n");
            for (BusinessEntityField f : fields) {
                if (isSystemField(f)) continue;
                if (f.required()) {
                    sb.append("        if (request.").append(f.name()).append("() == null")
                            .append(stringRequired(f) ? " || request." + f.name() + "().isBlank()" : "")
                            .append(") {\n");
                    sb.append("            throw new PlatformException(ErrorCode.of(\"").append(upper(entity)).append("_").append(upper(f.name())).append("_REQUIRED\", \"")
                            .append(f.name()).append(" is required\"));\n        }\n");
                }
                if (f.unique()) {
                    sb.append("        if (").append(entityVar).append("Port.existsByUnique(String.valueOf(request.").append(f.name()).append("()), id)) {\n");
                    sb.append("            throw new PlatformException(ErrorCode.of(\"").append(upper(entity)).append("_").append(upper(f.name())).append("_DUPLICATE\", \"")
                            .append(f.name()).append(" already exists\"));\n        }\n");
                }
                if (dictionary && "dictionary".equals(f.semantic()) && f.dictionary() != null) {
                    sb.append("        validateDictionary(\"").append(f.dictionary()).append("\", request.").append(f.name()).append("());\n");
                }
            }
            for (BusinessEntityField f : fields) {
                if (isSystemField(f)) continue;
                sb.append("        ").append(entityVar).append(".set").append(capitalize(f.name())).append("(request.").append(f.name()).append("());\n");
            }
            sb.append("        ").append(entityVar).append(".setUpdatedAt(LocalDateTime.now());\n");
            sb.append("        ").append(entityVar).append("Port.update(").append(entityVar).append(");\n");
            sb.append("        return toResponse(").append(entityVar).append(");\n    }\n\n");
        }

        // disable
        if (features.contains("disable")) {
            sb.append("    public ").append(entity).append("Response disable(Long id, DataPermissionContext context) {\n");
            sb.append("        ").append(entity).append(" ").append(entityVar).append(" = ").append(entityVar)
                    .append("Port.findByIdInScope(id, context)\n");
            sb.append("                .orElseThrow(() -> new PlatformException(ErrorCode.of(\"").append(upper(entity)).append("_NOT_FOUND\", \"")
                    .append(entityVar).append(" not found: \" + id)));\n");
            String statusField = statusField(fields);
            if (statusField != null) {
                sb.append("        ").append(entityVar).append(".set").append(capitalize(statusField)).append("(\"DISABLED\");\n");
            }
            sb.append("        ").append(entityVar).append(".setUpdatedAt(LocalDateTime.now());\n");
            sb.append("        ").append(entityVar).append("Port.update(").append(entityVar).append(");\n");
            sb.append("        return toResponse(").append(entityVar).append(");\n    }\n\n");
        }

        // dictionary validation helper
        if (dictionary) {
            sb.append("    private void validateDictionary(String code, String value) {\n");
            sb.append("        if (value == null || value.isBlank()) return;\n");
            sb.append("        dictionaryPort.findTypeByCode(code).ifPresent(type -> {\n");
            sb.append("            java.util.Set<String> values = dictionaryPort.findItemsByTypeId(type.getId()).stream()\n");
            sb.append("                    .map(DictionaryItem::getValue).collect(java.util.stream.Collectors.toSet());\n");
            sb.append("            if (!values.isEmpty() && !values.contains(value)) {\n");
            sb.append("                throw new PlatformException(ErrorCode.of(\"").append(upper(entity)).append("_DICTIONARY_INVALID\", \"value not in dictionary ").append("\" + code + \": \" + value));\n");
            sb.append("            }\n        });\n    }\n\n");
        }

        // toResponse
        sb.append("    private ").append(entity).append("Response toResponse(").append(entity).append(" e) {\n");
        sb.append("        return ").append(entity).append("Response.of(e.getId()");
        for (BusinessEntityField f : fields) {
            if (isSystemField(f)) continue;
            sb.append(", e.get").append(capitalize(f.name())).append("()");
        }
        if (dataScope) sb.append(", e.getDepartmentId()");
        sb.append(", e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private String mapperSource(String pkg, String entity) {
        return "package " + pkg + ".infrastructure.persistence.mapper;\n\n"
                + "import " + pkg + ".domain.entity." + entity + ";\n"
                + "import com.baomidou.mybatisplus.core.mapper.BaseMapper;\n"
                + "import org.apache.ibatis.annotations.Mapper;\n\n"
                + "/**\n * MyBatis-Plus mapper for " + entity + " (generated by engineering-platform Generic Module Generator).\n */\n"
                + "@Mapper\npublic interface " + entity + "Mapper extends BaseMapper<" + entity + "> {\n}\n";
    }

    private String repositorySource(String pkg, String modPkg, String entity, String entityVar, String table,
                                    List<BusinessEntityField> fields, boolean dataScope) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".infrastructure.persistence;\n\n");
        sb.append("import ").append(pkg).append(".application.").append(modPkg).append(".").append(entity).append("Port;\n");
        sb.append("import ").append(pkg).append(".application.datascope.DataPermissionContext;\n");
        sb.append("import ").append(pkg).append(".application.organization.DepartmentPort;\n");
        sb.append("import ").append(pkg).append(".common.core.PageQuery;\n");
        sb.append("import ").append(pkg).append(".common.core.PageResult;\n");
        sb.append("import ").append(pkg).append(".common.security.DataScope;\n");
        sb.append("import ").append(pkg).append(".domain.entity.").append(entity).append(";\n");
        sb.append("import ").append(pkg).append(".infrastructure.persistence.mapper.").append(entity).append("Mapper;\n");
        sb.append("import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;\n\n");
        sb.append("import java.util.List;\nimport java.util.Optional;\nimport java.util.Set;\n\n");
        sb.append("/**\n * MyBatis-Plus implementation of {@link ").append(entity).append("Port} (generated by engineering-platform Generic Module Generator).\n");
        if (dataScope) {
            sb.append(" * Data scope enforcement happens HERE at the query boundary: ALL / DEPARTMENT /\n");
            sb.append(" * DEPARTMENT_AND_CHILDREN / SELF. Fail-safe: missing department with a department-\n");
            sb.append(" * dependent scope denies (empty), never upgrades to ALL.\n");
        }
        sb.append(" */\n");
        sb.append("public class Mybatis").append(entity).append("Repository implements ").append(entity).append("Port {\n\n");
        sb.append("    private final ").append(entity).append("Mapper ").append(entityVar).append("Mapper;\n");
        if (dataScope) sb.append("    private final DepartmentPort departmentPort;\n");
        sb.append("\n    public Mybatis").append(entity).append("Repository(").append(entity).append("Mapper ").append(entityVar).append("Mapper");
        if (dataScope) sb.append(", DepartmentPort departmentPort");
        sb.append(") {\n        this.").append(entityVar).append("Mapper = ").append(entityVar).append("Mapper;\n");
        if (dataScope) sb.append("        this.departmentPort = departmentPort;\n");
        sb.append("    }\n\n");
        sb.append("    @Override\n    public Optional<").append(entity).append("> findById(Long id) {\n")
                .append("        return Optional.ofNullable(").append(entityVar).append("Mapper.selectById(id));\n    }\n\n");
        sb.append("    @Override\n    public Optional<").append(entity).append("> findByIdInScope(Long id, DataPermissionContext context) {\n")
                .append("        return findByScope(context).stream().filter(e -> e.getId().equals(id)).findFirst();\n    }\n\n");
        if (featuresList(fields, true)) { /* findByScope always generated when list/detail */ }
        sb.append("    @Override\n    public List<").append(entity).append("> findByScope(DataPermissionContext context) {\n");
        sb.append("        QueryWrapper<").append(entity).append("> wrapper = scopeWrapper(context);\n");
        sb.append("        wrapper.orderByAsc(\"id\");\n");
        sb.append("        return ").append(entityVar).append("Mapper.selectList(wrapper);\n    }\n\n");
        sb.append("    @Override\n    public PageResult<").append(entity).append("> findPageByScope(DataPermissionContext context, PageQuery query) {\n");
        sb.append("        QueryWrapper<").append(entity).append("> countWrapper = scopeWrapper(context);\n");
        sb.append("        long total = ").append(entityVar).append("Mapper.selectCount(countWrapper);\n");
        sb.append("        QueryWrapper<").append(entity).append("> pageWrapper = scopeWrapper(context);\n");
        sb.append("        pageWrapper.orderByAsc(\"id\");\n");
        sb.append("        pageWrapper.last(\"LIMIT \" + query.size() + \" OFFSET \" + query.offset());\n");
        sb.append("        List<").append(entity).append("> items = ").append(entityVar).append("Mapper.selectList(pageWrapper);\n");
        sb.append("        return PageResult.of(items, total, query.page(), query.size());\n    }\n\n");
        sb.append("    @Override\n    public PageResult<").append(entity).append("> findPageByScopeFiltered(DataPermissionContext context, PageQuery query, String keyword, String status");
        for (BusinessEntityField f : fields) {
            if (isSystemField(f)) continue;
            if (Boolean.TRUE.equals(f.frontend().get("searchable"))) {
                sb.append(", String ").append(f.name());
            }
        }
        sb.append(") {\n");
        sb.append("        QueryWrapper<").append(entity).append("> countWrapper = scopeWrapper(context);\n");
        sb.append("        applyFilters(countWrapper, keyword, status");
        for (BusinessEntityField f : fields) {
            if (isSystemField(f)) continue;
            if (Boolean.TRUE.equals(f.frontend().get("searchable"))) {
                sb.append(", ").append(f.name());
            }
        }
        sb.append(");\n");
        sb.append("        long total = ").append(entityVar).append("Mapper.selectCount(countWrapper);\n");
        sb.append("        QueryWrapper<").append(entity).append("> pageWrapper = scopeWrapper(context);\n");
        sb.append("        applyFilters(pageWrapper, keyword, status");
        for (BusinessEntityField f : fields) {
            if (isSystemField(f)) continue;
            if (Boolean.TRUE.equals(f.frontend().get("searchable"))) {
                sb.append(", ").append(f.name());
            }
        }
        sb.append(");\n");
        sb.append("        pageWrapper.orderByAsc(\"id\");\n");
        sb.append("        pageWrapper.last(\"LIMIT \" + query.size() + \" OFFSET \" + query.offset());\n");
        sb.append("        List<").append(entity).append("> items = ").append(entityVar).append("Mapper.selectList(pageWrapper);\n");
        sb.append("        return PageResult.of(items, total, query.page(), query.size());\n    }\n\n");
        sb.append("    private void applyFilters(QueryWrapper<").append(entity).append("> wrapper, String keyword, String status");
        for (BusinessEntityField f : fields) {
            if (isSystemField(f)) continue;
            if (Boolean.TRUE.equals(f.frontend().get("searchable"))) {
                sb.append(", String ").append(f.name());
            }
        }
        sb.append(") {\n");
        // per-field searchable filters (frontend.searchable=true)
        for (BusinessEntityField f : fields) {
            if (isSystemField(f)) continue;
            if (Boolean.TRUE.equals(f.frontend().get("searchable"))) {
                sb.append("        if (").append(f.name()).append(" != null && !").append(f.name()).append(".isBlank()) {\n");
                sb.append("            wrapper.like(\"").append(f.name()).append("\", ").append(f.name()).append(");\n        }\n");
            }
        }
        List<String> stringFields = new ArrayList<>();
        for (BusinessEntityField f : fields) {
            if (isSystemField(f)) continue;
            if ("string".equals(f.type()) || "text".equals(f.type())) stringFields.add(f.name());
        }
        if (!stringFields.isEmpty()) {
            sb.append("        if (keyword != null && !keyword.isBlank()) {\n");
            sb.append("            wrapper.and(w -> {");
            for (int i = 0; i < stringFields.size(); i++) {
                if (i > 0) sb.append(" ");
                sb.append(i == 0 ? "w.like(\"" + stringFields.get(i) + "\", keyword)" : ".or().like(\"" + stringFields.get(i) + "\", keyword)");
            }
            sb.append("; });\n");
            sb.append("        }\n");
        }
        String statusField = statusField(fields);
        if (statusField != null) {
            sb.append("        if (status != null && !status.isBlank()) {\n");
            sb.append("            wrapper.eq(\"").append(statusField).append("\", status);\n        }\n");
        }
        sb.append("    }\n\n");
        sb.append("    private QueryWrapper<").append(entity).append("> scopeWrapper(DataPermissionContext context) {\n");
        sb.append("        QueryWrapper<").append(entity).append("> wrapper = new QueryWrapper<>();\n");
        if (dataScope) {
            sb.append("        DataScope scope = context.scope() == null ? DataScope.SELF : context.scope();\n");
            sb.append("        switch (scope) {\n");
            sb.append("            case ALL -> { }\n");
            sb.append("            case DEPARTMENT -> {\n");
            sb.append("                if (context.departmentId() == null) { wrapper.eq(\"id\", -1L); } else { wrapper.eq(\"department_id\", context.departmentId()); }\n");
            sb.append("            }\n");
            sb.append("            case DEPARTMENT_AND_CHILDREN -> {\n");
            sb.append("                if (context.departmentId() == null) { wrapper.eq(\"id\", -1L); } else {\n");
            sb.append("                    Set<Long> ids = departmentPort.descendantIds(context.departmentId());\n");
            sb.append("                    ids.add(context.departmentId());\n");
            sb.append("                    wrapper.in(\"department_id\", ids);\n");
            sb.append("                }\n");
            sb.append("            }\n");
            sb.append("            case SELF -> {\n");
            sb.append("                if (context.userId() == null) { wrapper.eq(\"id\", -1L); } else { wrapper.eq(\"created_by\", String.valueOf(context.userId())); }\n");
            sb.append("            }\n");
            sb.append("        }\n");
        }
        sb.append("        return wrapper;\n    }\n\n");
        sb.append("    @Override\n    public boolean existsByUnique(String value, Long excludeId) {\n");
        String uniqueField = firstUniqueFieldName(fields);
        if (uniqueField != null) {
            sb.append("        QueryWrapper<").append(entity).append("> wrapper = new QueryWrapper<>();\n");
            sb.append("        wrapper.eq(\"").append(uniqueField).append("\", value);\n");
            sb.append("        if (excludeId != null) { wrapper.ne(\"id\", excludeId); }\n");
            sb.append("        return ").append(entityVar).append("Mapper.selectCount(wrapper) > 0;\n");
        } else {
            sb.append("        return false;\n");
        }
        sb.append("    }\n\n");
        sb.append("    @Override\n    public ").append(entity).append(" insert(").append(entity).append(" entity) {\n")
                .append("        ").append(entityVar).append("Mapper.insert(entity);\n        return entity;\n    }\n\n");
        sb.append("    @Override\n    public int update(").append(entity).append(" entity) {\n")
                .append("        return ").append(entityVar).append("Mapper.updateById(entity);\n    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private String beansConfigSource(String pkg, String modPkg, String entity, boolean dataScope,
                                     boolean dictionary) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".infrastructure.persistence;\n\n");
        sb.append("import ").append(pkg).append(".application.").append(modPkg).append(".").append(entity).append("Port;\n");
        sb.append("import ").append(pkg).append(".application.").append(modPkg).append(".").append(entity).append("Service;\n");
        if (dataScope) sb.append("import ").append(pkg).append(".application.organization.DepartmentPort;\n");
        if (dictionary) sb.append("import ").append(pkg).append(".application.dictionary.DictionaryPort;\n");
        sb.append("import ").append(pkg).append(".infrastructure.persistence.mapper.").append(entity).append("Mapper;\n");
        sb.append("import org.springframework.context.annotation.Bean;\n");
        sb.append("import org.springframework.context.annotation.Configuration;\n\n");
        sb.append("/**\n * ").append(entity).append(" bean wiring (generated by engineering-platform Generic Module Generator).\n */\n");
        sb.append("@Configuration\npublic class ").append(entity).append("BeansConfig {\n\n");
        sb.append("    @Bean\n    public ").append(entity).append("Port ").append(decapitalize(entity)).append("Port(").append(entity).append("Mapper mapper");
        if (dataScope) sb.append(", DepartmentPort departmentPort");
        sb.append(") {\n");
        sb.append("        return new Mybatis").append(entity).append("Repository(mapper");
        if (dataScope) sb.append(", departmentPort");
        sb.append(");\n    }\n\n");
        sb.append("    @Bean\n    public ").append(entity).append("Service ").append(decapitalize(entity)).append("Service(").append(entity).append("Port port");
        if (dictionary) sb.append(", DictionaryPort dictionaryPort");
        sb.append(") {\n");
        sb.append("        return new ").append(entity).append("Service(port");
        if (dictionary) sb.append(", dictionaryPort");
        sb.append(");\n    }\n}\n");
        return sb.toString();
    }

    private String controllerSource(String pkg, String modPkg, String entity, String entityVar,
                                    List<String> features, boolean dataScope, boolean permissions,
                                    boolean operationLog, String table, List<BusinessEntityField> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".api.").append(modPkg).append(";\n\n");
        sb.append("import ").append(pkg).append(".application.datascope.DataPermissionContext;\n");
        sb.append("import ").append(pkg).append(".application.datascope.DataScopeResolver;\n");
        sb.append("import ").append(pkg).append(".application.").append(modPkg).append(".").append(entity).append("CreateRequest;\n");
        sb.append("import ").append(pkg).append(".application.").append(modPkg).append(".").append(entity).append("Response;\n");
        sb.append("import ").append(pkg).append(".application.").append(modPkg).append(".").append(entity).append("Service;\n");
        sb.append("import ").append(pkg).append(".application.").append(modPkg).append(".").append(entity).append("UpdateRequest;\n");
        sb.append("import ").append(pkg).append(".common.core.ApiResponse;\n");
        sb.append("import ").append(pkg).append(".common.core.PageQuery;\n");
        sb.append("import ").append(pkg).append(".common.core.PageResult;\n");
        sb.append("import ").append(pkg).append(".common.core.PlatformException;\n");
        sb.append("import ").append(pkg).append(".common.core.RequestContext;\n");
        sb.append("import ").append(pkg).append(".common.security.OperationLog;\n");
        sb.append("import ").append(pkg).append(".common.security.RequirePermission;\n");
        sb.append("import ").append(pkg).append(".common.security.SecurityErrorCodes;\n");
        sb.append("import org.springframework.web.bind.annotation.*;\n\n");
        sb.append("import java.util.List;\n\n");
        sb.append("/**\n * ").append(entity).append(" CRUD API (generated by engineering-platform Generic Module Generator).\n */\n");
        sb.append("@RestController\n@RequestMapping(\"/api/").append(table).append("\")\n");
        sb.append("public class ").append(entity).append("Controller {\n\n");
        sb.append("    private final ").append(entity).append("Service ").append(entityVar).append("Service;\n");
        if (dataScope) sb.append("    private final DataScopeResolver dataScopeResolver;\n");
        sb.append("\n    public ").append(entity).append("Controller(").append(entity).append("Service ").append(entityVar).append("Service");
        if (dataScope) sb.append(", DataScopeResolver dataScopeResolver");
        sb.append(") {\n        this.").append(entityVar).append("Service = ").append(entityVar).append("Service;\n");
        if (dataScope) sb.append("        this.dataScopeResolver = dataScopeResolver;\n");
        sb.append("    }\n\n");
        sb.append("    private Long currentUserId() {\n");
        sb.append("        return Long.valueOf(RequestContext.currentUserId()\n");
        sb.append("                .orElseThrow(() -> new PlatformException(SecurityErrorCodes.UNAUTHENTICATED)));\n    }\n\n");
        if (dataScope) {
            sb.append("    private DataPermissionContext scope() {\n");
            sb.append("        return dataScopeResolver.resolve(currentUserId());\n    }\n\n");
        } else {
            sb.append("    private DataPermissionContext scope() {\n");
            sb.append("        return DataPermissionContext.of(currentUserId(), null, ").append(pkg).append(".common.security.DataScope.ALL);\n    }\n\n");
        }
        if (features.contains("create")) {
            sb.append("    @PostMapping\n");
            if (permissions) sb.append("    @RequirePermission(\"").append(table).append(":item:create\")\n");
            if (operationLog) sb.append("    @OperationLog(operation = \"").append(upper(entity)).append("_CREATE\", resourceType = \"").append(upper(entity)).append("\")\n");
            sb.append("    public ApiResponse<").append(entity).append("Response> create(@RequestBody ").append(entity).append("CreateRequest request) {\n");
            sb.append("        Long userId = currentUserId();\n");
            // V06-FINAL: departmentId comes from DataScopeResolver (authoritative user
            // department), NOT from RequestContext — AuthInterceptor only fills userId,
            // so RequestContext.currentDepartmentId() is always empty and DEPARTMENT-
            // scoped users could never see records they created.
            sb.append("        Long departmentId = scope().departmentId();\n");
            sb.append("        return ApiResponse.ofSuccess(").append(entityVar).append("Service.create(request, userId, departmentId));\n    }\n\n");
        }
        if (features.contains("detail")) {
            sb.append("    @GetMapping(\"/{id}\")\n");
            if (permissions) sb.append("    @RequirePermission(\"").append(table).append(":item:read\")\n");
            sb.append("    public ApiResponse<").append(entity).append("Response> detail(@PathVariable Long id) {\n");
            sb.append("        return ApiResponse.ofSuccess(").append(entityVar).append("Service.get(id, scope()));\n    }\n\n");
        }
        if (features.contains("list")) {
            sb.append("    @GetMapping\n");
            if (permissions) sb.append("    @RequirePermission(\"").append(table).append(":item:read\")\n");
            sb.append("    public ApiResponse<PageResult<").append(entity).append("Response>> list(\n");
            sb.append("            @RequestParam(defaultValue = \"1\") int page,\n");
            sb.append("            @RequestParam(defaultValue = \"20\") int size,\n");
            sb.append("            @RequestParam(required = false) String keyword");
            if (features.contains("search")) sb.append(",\n            @RequestParam(required = false) String status");
            // per-field searchable filters (frontend.searchable=true)
            for (BusinessEntityField f : fields) {
                if (isSystemField(f)) continue;
                if (Boolean.TRUE.equals(f.frontend().get("searchable"))) {
                    sb.append(",\n            @RequestParam(required = false) String ").append(f.name());
                }
            }
            sb.append(") {\n");
            if (features.contains("search")) {
                sb.append("        return ApiResponse.ofSuccess(").append(entityVar).append("Service.pageFiltered(PageQuery.of(page, size), scope(), keyword, status");
                for (BusinessEntityField f : fields) {
                    if (isSystemField(f)) continue;
                    if (Boolean.TRUE.equals(f.frontend().get("searchable"))) {
                        sb.append(", ").append(f.name());
                    }
                }
                sb.append("));\n");
            } else {
                sb.append("        return ApiResponse.ofSuccess(").append(entityVar).append("Service.page(PageQuery.of(page, size), scope()));\n");
            }
            sb.append("    }\n\n");
        }
        if (features.contains("edit")) {
            sb.append("    @PutMapping(\"/{id}\")\n");
            if (permissions) sb.append("    @RequirePermission(\"").append(table).append(":item:update\")\n");
            if (operationLog) sb.append("    @OperationLog(operation = \"").append(upper(entity)).append("_UPDATE\", resourceType = \"").append(upper(entity)).append("\")\n");
            sb.append("    public ApiResponse<").append(entity).append("Response> update(@PathVariable Long id, @RequestBody ").append(entity).append("UpdateRequest request) {\n");
            sb.append("        return ApiResponse.ofSuccess(").append(entityVar).append("Service.update(id, request, scope()));\n    }\n\n");
        }
        if (features.contains("disable")) {
            sb.append("    @PostMapping(\"/{id}/disable\")\n");
            if (permissions) sb.append("    @RequirePermission(\"").append(table).append(":item:disable\")\n");
            if (operationLog) sb.append("    @OperationLog(operation = \"").append(upper(entity)).append("_DISABLE\", resourceType = \"").append(upper(entity)).append("\")\n");
            sb.append("    public ApiResponse<").append(entity).append("Response> disable(@PathVariable Long id) {\n");
            sb.append("        return ApiResponse.ofSuccess(").append(entityVar).append("Service.disable(id, scope()));\n    }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private String migrationSource(String table, List<BusinessEntityField> fields, boolean dataScope) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- ").append(table).append(" schema (generated by engineering-platform Generic Module Generator)\n");
        sb.append("-- MySQL-compatible baseline (H2 MODE=MySQL for tests).\n\n");
        sb.append("CREATE TABLE IF NOT EXISTS ").append(table).append(" (\n");
        sb.append("    id            BIGINT       NOT NULL AUTO_INCREMENT,\n");
        for (BusinessEntityField f : fields) {
            if (isSystemField(f)) continue;
            sb.append("    ").append(sqlColumn(f));
        }
        if (dataScope) sb.append("    department_id BIGINT       NULL,\n");
        sb.append("    created_by    VARCHAR(64)  NULL,\n");
        sb.append("    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,\n");
        sb.append("    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,\n");
        sb.append("    PRIMARY KEY (id)\n");
        for (BusinessEntityField f : fields) {
            if (f.unique()) {
                sb.append("    ,\n    UNIQUE KEY uk_").append(table).append("_").append(f.name()).append(" (").append(f.name()).append(")");
            }
        }
        sb.append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n");
        return sb.toString();
    }

    private String seedSource(ResolvedBusinessModule module, Ids ids, List<BusinessEntityField> fields,
                              boolean dataScope, boolean permissions, boolean dictionary, boolean menu,
                              String route, String table) {
        String moduleId = module.id();
        StringBuilder sb = new StringBuilder();
        sb.append("-- TEST-ONLY ").append(moduleId).append(" seed (generated by engineering-platform Generic Module Generator)\n");
        sb.append("-- Deterministic ID allocation (module sorted index ").append(ids.idx()).append(") — never hand-written.\n");
        sb.append("-- Loaded only by the test profile.\n\n");
        if (permissions) {
            sb.append("INSERT INTO sys_permission (id, code, name, created_at, updated_at) VALUES\n");
            sb.append("  (").append(ids.permission(0)).append(", '").append(table).append(":item:read', 'Read ").append(module.name()).append("s', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),\n");
            sb.append("  (").append(ids.permission(1)).append(", '").append(table).append(":item:create', 'Create ").append(module.name()).append("s', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),\n");
            sb.append("  (").append(ids.permission(2)).append(", '").append(table).append(":item:update', 'Update ").append(module.name()).append("s', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),\n");
            sb.append("  (").append(ids.permission(3)).append(", '").append(table).append(":item:disable', 'Disable ").append(module.name()).append("s', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);\n\n");
            sb.append("INSERT INTO sys_role_permission (id, role_id, permission_id, created_at) VALUES\n");
            sb.append("  (").append(ids.rolePermission(0)).append(", 1, ").append(ids.permission(0)).append(", CURRENT_TIMESTAMP),\n");
            sb.append("  (").append(ids.rolePermission(1)).append(", 1, ").append(ids.permission(1)).append(", CURRENT_TIMESTAMP),\n");
            sb.append("  (").append(ids.rolePermission(2)).append(", 1, ").append(ids.permission(2)).append(", CURRENT_TIMESTAMP),\n");
            sb.append("  (").append(ids.rolePermission(3)).append(", 1, ").append(ids.permission(3)).append(", CURRENT_TIMESTAMP);\n\n");
        }
        // dictionary seeds
        if (dictionary) {
            int dictIdx = 0;
            int itemIdx = 0;
            for (BusinessEntityField f : fields) {
                if ("dictionary".equals(f.semantic()) && f.dictionary() != null) {
                    String code = f.dictionary();
                    sb.append("INSERT INTO sys_dictionary_type (id, code, name, enabled, description, created_at, updated_at) VALUES\n");
                    sb.append("  (").append(ids.dictType(dictIdx)).append(", '").append(code).append("', '").append(capitalize(code)).append("', 1, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);\n");
                    sb.append("INSERT INTO sys_dictionary_item (id, type_id, `value`, label, enabled, sort, description, created_at, updated_at) VALUES\n");
                    sb.append("  (").append(ids.dictItem(itemIdx++)).append(", ").append(ids.dictType(dictIdx)).append(", 'ENABLED', 'Enabled', 1, 1, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),\n");
                    sb.append("  (").append(ids.dictItem(itemIdx++)).append(", ").append(ids.dictType(dictIdx)).append(", 'DISABLED', 'Disabled', 1, 2, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);\n\n");
                    dictIdx++;
                }
            }
        }
        // menu
        if (menu) {
            sb.append("INSERT INTO sys_menu (id, parent_id, code, name, type, path, permission_code, enabled, sort, created_at, updated_at) VALUES\n");
            sb.append("  (").append(ids.menu(0)).append(", NULL, '").append(moduleId).append("', '").append(module.name()).append("s', 'DIRECTORY', '").append(route).append("', NULL, 1, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),\n");
            sb.append("  (").append(ids.menu(1)).append(", ").append(ids.menu(0)).append(", '").append(moduleId).append("-list', '").append(module.name()).append(" list', 'MENU', '").append(route).append("', '").append(table).append(":item:read', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);\n\n");
        }
        // sample rows (2)
        sb.append("INSERT INTO ").append(table).append(" (id");
        for (BusinessEntityField f : fields) {
            if (isSystemField(f)) continue;
            sb.append(", ").append(f.name());
        }
        if (dataScope) sb.append(", department_id");
        sb.append(", created_by, created_at, updated_at) VALUES\n");
        for (int row = 1; row <= 2; row++) {
            sb.append("  (").append(row);
            for (BusinessEntityField f : fields) {
                if (isSystemField(f)) continue;
                sb.append(", ").append(sampleValue(f, row));
            }
            if (dataScope) sb.append(", 1");
            sb.append(", 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            sb.append(row == 2 ? ";\n" : ",\n");
        }
        return sb.toString();
    }

    private String modelUnitTestSource(String pkg, String modPkg, String entity, String entityVar,
                                       List<BusinessEntityField> fields, List<String> features,
                                       boolean dataScope, boolean dictionary, String table) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("import ").append(pkg).append(".domain.entity.").append(entity).append(";\n");
        sb.append("import org.junit.jupiter.api.Test;\n\n");
        sb.append("import static org.assertj.core.api.Assertions.assertThat;\n\n");
        sb.append("/**\n * ").append(entity).append(" model unit tests (generated by engineering-platform Generic Module Generator).\n */\n");
        sb.append("class ").append(entity).append("ModelUnitTest {\n\n");
        sb.append("    @Test\n    void entityAccessorsRoundTrip() {\n");
        sb.append("        ").append(entity).append(" e = new ").append(entity).append("();\n");
        sb.append("        e.setId(1L);\n");
        for (BusinessEntityField f : fields) {
            if (isSystemField(f)) continue;
            String sample = javaSampleValue(f);
            sb.append("        e.set").append(capitalize(f.name())).append("(").append(sample).append(");\n");
        }
        if (dataScope) sb.append("        e.setDepartmentId(1L);\n");
        sb.append("        e.setCreatedBy(1L);\n");
        sb.append("        assertThat(e.getId()).isEqualTo(1L);\n");
        for (BusinessEntityField f : fields) {
            if (isSystemField(f)) continue;
            sb.append("        assertThat(e.get").append(capitalize(f.name())).append("()).isEqualTo(")
                    .append(javaSampleValue(f)).append(");\n");
        }
        if (dataScope) sb.append("        assertThat(e.getDepartmentId()).isEqualTo(1L);\n");
        sb.append("        assertThat(e.getCreatedBy()).isEqualTo(1L);\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Frontend
    // ------------------------------------------------------------------

    private static boolean bool(Map<String, Object> map, String key) {
        return Boolean.TRUE.equals(map.get(key));
    }

    private static String str(Map<String, Object> map, String key, String dflt) {
        Object v = map.get(key);
        return v == null ? dflt : String.valueOf(v);
    }

    private static boolean isSystemField(BusinessEntityField f) {
        return f.semantic() != null && ("system".equals(f.semantic()) || "ownership".equals(f.semantic())
                || "currentUser".equals(f.semantic()));
    }

    private static String javaType(BusinessEntityField f) {
        return switch (f.type() == null ? "string" : f.type()) {
            case "integer" -> "Long";
            case "number", "decimal" -> "BigDecimal";
            case "boolean" -> "Boolean";
            case "date" -> "LocalDate";
            case "datetime" -> "LocalDateTime";
            default -> "String";
        };
    }

    private static String javaSampleValue(BusinessEntityField f) {
        return switch (f.type() == null ? "string" : f.type()) {
            case "boolean" -> "true";
            case "integer" -> "1L";
            case "number", "decimal" -> "new java.math.BigDecimal(\"1.5\")";
            case "date" -> "java.time.LocalDate.of(2026, 8, 16)";
            case "datetime" -> "java.time.LocalDateTime.now()";
            default -> "\"v\"";
        };
    }

    private static String sqlColumn(BusinessEntityField f) {
        String type = f.type() == null ? "string" : f.type();
        StringBuilder sb = new StringBuilder("    ").append(f.name()).append(" ");
        switch (type) {
            case "text" -> sb.append("TEXT");
            case "integer" -> sb.append("BIGINT");
            case "number", "decimal" -> {
                int precision = f.precision() == null ? 18 : f.precision();
                int scale = f.scale() == null ? 2 : f.scale();
                sb.append("DECIMAL(").append(precision).append(", ").append(scale).append(")");
            }
            case "boolean" -> sb.append("BOOLEAN");
            case "date" -> sb.append("DATE");
            case "datetime" -> sb.append("DATETIME");
            default -> {
                int length = f.length() == null ? 255 : f.length();
                sb.append("VARCHAR(").append(length).append(")");
            }
        }
        if (f.required()) sb.append(" NOT NULL");
        // V06-WORK-005: defaultValue from contract → DEFAULT clause (H2 MODE=MySQL compatible)
        if (f.defaultValue() != null) {
            String dv = String.valueOf(f.defaultValue());
            String literal = switch (f.type() == null ? "string" : f.type()) {
                case "boolean" -> "1".equals(dv) || "true".equalsIgnoreCase(dv) ? "1" : "0";
                case "integer", "number", "decimal" -> dv;
                case "date" -> "DATE '" + dv + "'";
                case "datetime" -> "TIMESTAMP '" + dv + "'";
                default -> "'" + dv.replace("'", "''") + "'";
            };
            sb.append(" DEFAULT ").append(literal);
        }
        sb.append(",\n");
        return sb.toString();
    }

    private static String sampleValue(BusinessEntityField f, int row) {
        return switch (f.type() == null ? "string" : f.type()) {
            case "boolean" -> "1";
            case "integer" -> String.valueOf(row);
            case "number", "decimal" -> String.valueOf(row) + ".5";
            case "date" -> "CURRENT_DATE";
            case "datetime" -> "CURRENT_TIMESTAMP";
            default -> "'" + f.name() + "-" + row + "'";
        };
    }

    private static boolean hasType(List<BusinessEntityField> fields, String type) {
        return fields.stream().anyMatch(f -> type.equals(f.type()));
    }

    private static boolean hasSemantic(List<BusinessEntityField> fields, String semantic) {
        return fields.stream().anyMatch(f -> semantic.equals(f.semantic()));
    }

    private static String statusField(List<BusinessEntityField> fields) {
        for (BusinessEntityField f : fields) {
            if (isSystemField(f)) continue;
            if ("status".equals(f.name()) || "state".equals(f.name())) return f.name();
        }
        return null;
    }

    private static String firstUniqueFieldName(List<BusinessEntityField> fields) {
        for (BusinessEntityField f : fields) {
            if (isSystemField(f)) continue;
            if (f.unique()) return f.name();
        }
        return null;
    }

    private static String firstUniqueField(List<String> features) {
        return features.contains("create") || features.contains("edit") ? "unique" : null;
    }

    private static boolean featuresList(List<BusinessEntityField> fields, boolean unused) {
        return true;
    }

    private static boolean stringRequired(BusinessEntityField f) {
        return "string".equals(f.type()) || "text".equals(f.type());
    }

    private static String frontendRoute(ResolvedBusinessModule module) {
        Object route = module.frontend().get("route");
        if (route != null) return String.valueOf(route);
        return "/" + module.id().replace("-", "");
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String decapitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String upper(String s) {
        return s.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT);
    }
}
