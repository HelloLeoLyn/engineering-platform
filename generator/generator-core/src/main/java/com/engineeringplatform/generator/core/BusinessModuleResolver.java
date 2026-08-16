package com.engineeringplatform.generator.core;

import com.engineeringplatform.generator.contracts.BusinessEntityField;
import com.engineeringplatform.generator.contracts.IntermediateResolutionState;
import com.engineeringplatform.generator.contracts.ResolutionError;
import com.engineeringplatform.generator.contracts.ResolvedBusinessModule;
import com.engineeringplatform.generator.contracts.ResolverInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V06-WORK-001 — Generic Business Module Resolver.
 *
 * Reads the optional {@code business} section of module manifests referenced by
 * project.modules and produces structured {@link ResolvedBusinessModule} records
 * (entity/fields/features/enterprise/frontend) that enter the EPM as structured
 * input for the future Module Generator (WORK-002) — not raw Maps.
 *
 * Uses the existing moduleManifests channel (same as DependencyResolver); no
 * second Resolver.
 */
public final class BusinessModuleResolver {

    /**
     * Resolves business definitions for all explicit modules that declare one.
     * Modules without a business section are left untouched (platform modules).
     */
    public void resolve(ResolverInput input, IntermediateResolutionState.Builder state) {
        List<String> moduleIds = ReferenceResolver.extractIds(input.projectManifest().get("modules"));
        for (String id : moduleIds) {
            Map<String, Object> manifest = input.moduleManifests().get(id);
            if (manifest == null) {
                continue;
            }
            Object business = manifest.get("business");
            if (!(business instanceof Map<?, ?> businessMap)) {
                continue; // not a business module
            }
            ResolvedBusinessModule module = parseModule(id, manifest, asMap(business));
            if (module != null) {
                state.businessModule(module);
            }
        }
    }

    private ResolvedBusinessModule parseModule(String id, Map<String, Object> manifest, Map<String, Object> business) {
        Map<String, Object> moduleMeta = asMap(manifest.get("module"));
        String name = str(moduleMeta.get("name"), id);
        String version = moduleMeta.get("version") instanceof String v ? v : null;
        String table = str(business.get("table"), id);

        ResolvedBusinessModule.BusinessEntity entity = parseEntity(asMap(business.get("entity")));
        List<String> features = parseFeatures(business.get("features"));
        Map<String, Object> enterprise = asMap(business.get("enterprise"));
        Map<String, Object> frontend = asMap(business.get("frontend"));

        return new ResolvedBusinessModule(id, name, version, table, entity, features, enterprise, frontend);
    }

    private ResolvedBusinessModule.BusinessEntity parseEntity(Map<String, Object> entityMap) {
        String name = str(entityMap.get("name"), "Entity");
        List<BusinessEntityField> fields = new ArrayList<>();
        Object fieldsVal = entityMap.get("fields");
        if (fieldsVal instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> f = asMap(m);
                    fields.add(new BusinessEntityField(
                            str(f.get("name"), ""),
                            str(f.get("type"), "string"),
                            bool(f.get("required")),
                            bool(f.get("unique")),
                            intOrNull(f.get("length")),
                            intOrNull(f.get("precision")),
                            intOrNull(f.get("scale")),
                            f.get("default"),
                            bool(f.get("primaryKey")),
                            str(f.get("comment"), null),
                            str(f.get("semantic"), "none"),
                            str(f.get("dictionary"), null),
                            asMap(f.get("frontend"))));
                }
            }
        }
        return new ResolvedBusinessModule.BusinessEntity(name, fields);
    }

    private List<String> parseFeatures(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) {
                    result.add(s);
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Object value, String defaultValue) {
        return value instanceof String s ? s : defaultValue;
    }

    private static boolean bool(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static Integer intOrNull(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return null;
    }
}
